package org.zaretkim.dividendsrobot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Runner that schedules and executes @{@link PreDividendsStrategyService::step} once a day
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RobotRunner {
    private static final String ZONE_MOSCOW = "Europe/Moscow";
    private final PreDividendsStrategyService preDividendsStrategyService;
    private final SandboxMarketService sandboxMarketService;
    private final BacktestMarketService backtestMarketService;
    private final RealMarketService realMarketService;
    private Timer timer;
    private boolean executingStep = false;
    private final Object lockObject = new Object();

    public String start() {
        stopRunningRobot();
        preDividendsStrategyService.setMarketService(realMarketService);
        var success = startRobot();
        if (success)
            return "Robot is started";
        else
            return "Failed to start robot. Try again later";
    }
    public String startSandbox() {
        stopRunningRobot();
        preDividendsStrategyService.setMarketService(sandboxMarketService);
        var success = startRobot();
        if (success)
            return "Robot is started in sandbox";
        else
            return "Failed to start robot. Try again later";
    }

    public String startBacktest() {
        backtestMarketService.Reset();
        preDividendsStrategyService.setMarketService(backtestMarketService);
        final int backDays = 365;
        var sb = new StringBuilder();
        BigDecimal initialFunds = preDividendsStrategyService.totalAmountOfFunds(backtestMarketService.getPortfolio());
        sb.append("Backtest is started with initial funds ").
                append(initialFunds).
                append(" on historical data for the last ").append(backDays).append(" days<br>");
        LocalDateTime localDateTime = LocalDateTime.now().minus(backDays, ChronoUnit.DAYS);

        Instant fakeTime = localDateTime.toInstant(ZoneOffset.of("+03:00:00"));
        try {
            for (int i = 0; i < backDays; i++) {
                backtestMarketService.setFakeNow(fakeTime);
                preDividendsStrategyService.step();
                fakeTime = fakeTime.plus(1, ChronoUnit.DAYS);
            }
        } catch (Throwable t) {
            log.info(t.getMessage(), t);
            return "Please, try again later. Backtest failed with error: " + t.getMessage();
        }
        var result = preDividendsStrategyService.totalAmountOfFunds(backtestMarketService.getPortfolio());
        sb.append("Final result: ").append(result).append("<br>");
        var yield = result.subtract(initialFunds).divide(initialFunds, RoundingMode.CEILING).multiply(BigDecimal.valueOf(100));
        sb.append("Yield: ").append(yield).append("%");
        return sb.toString();
    }

    public void stopRunningRobot() {
        synchronized (lockObject) {
            if (timer == null) return;
            timer.cancel();
            timer = null;
            if (executingStep) {
                try {
                    lockObject.wait();
                } catch (InterruptedException e) {
                    log.error(e.getMessage(), e);
                }
            }
        }
    }

    private boolean startRobot() {
        synchronized (lockObject) {
            if (timer != null) return false;
            timer = new Timer();
        }
        executeRobotStepWithRescheduleOnError(0);
        LocalDateTime tomorrowMiddayLocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.DAYS).plus(36, ChronoUnit.HOURS);
        Date tomorrowMiddayDate = Date.from(tomorrowMiddayLocalDateTime.atZone(ZoneId.of(ZONE_MOSCOW)).toInstant());
        TimerTask timerTask = new TimerTask() {
            @Override
            public void run() {
                executeRobotStepWithRescheduleOnError(0);
            }
        };
        final int every_24_hours = 24 * 60 * 60 * 1000;
        timer.scheduleAtFixedRate(timerTask, tomorrowMiddayDate, every_24_hours);
        return true;
    }

    private void executeRobotStepWithRescheduleOnError(int tryNumber) {
        var stepResult = executeRobotStep();
        if (!stepResult ) {
            if (tryNumber > 8) {
                log.info("Failed to execute next step. Stop rescheduling, wait for next day");
                return;
            }
            log.info("Failed to execute next step. Try again in 30 minutes");
            TimerTask timerTask = new TimerTask() {
                @Override
                public void run() {
                    executeRobotStepWithRescheduleOnError(tryNumber + 1);
                }
            };
            final int in_30_minutes = 30 * 60 * 1000;
            timer.schedule(timerTask,  in_30_minutes);
        }
    }

    private boolean executeRobotStep() {
        synchronized (lockObject) {
            executingStep = true;
        }
        var stepResult = preDividendsStrategyService.step();
        synchronized (lockObject) {
            executingStep = false;
            lockObject.notifyAll();
        }
        return stepResult;
    }
}
