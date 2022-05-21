package org.zaretkim.dividendsrobot.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zaretkim.dividendsrobot.service.BacktestMarketService;
import org.zaretkim.dividendsrobot.service.RealMarketService;
import org.zaretkim.dividendsrobot.service.SandboxMarketService;
import org.zaretkim.dividendsrobot.service.StrategyService;
import ru.tinkoff.piapi.contract.v1.PortfolioPosition;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.utils.MapperUtils;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.Function;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ApplicationController {
    private static final String ZONE_MOSCOW = "Europe/Moscow";
    private final StrategyService strategyService;
    private final SandboxMarketService sandboxMarketService;
    private final BacktestMarketService backtestMarketService;
    private final RealMarketService realMarketService;
    private Timer timer;
    private boolean executingStep = false;
    private final Object lockObject = new Object();

    @GetMapping("/startSandbox")
    public String startSandbox() {
        String validateTokenErrorMessage = sandboxMarketService.validateToken();
        if (validateTokenErrorMessage != null) {
            return validateTokenErrorMessage;
        }

        stopRunningRobot();
        strategyService.setMarketService(sandboxMarketService);
        var success = startRobot();
        if (success)
            return "Robot is started in sandbox";
        else
            return "Failed to start robot. Try again later";
    }

    @GetMapping("/start")
    public String start() {
        String validateTokenErrorMessage = realMarketService.validateToken();
        if (validateTokenErrorMessage != null) {
            return validateTokenErrorMessage;
        }

        stopRunningRobot();
        strategyService.setMarketService(realMarketService);
        var success = startRobot();
        if (success)
            return "Robot is started";
        else
            return "Failed to start robot. Try again later";
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
            final int in_30_minutes = 3 * 60 * 1000;
            timer.schedule(timerTask,  in_30_minutes);
        }
    }
    private boolean executeRobotStep() {
        synchronized (lockObject) {
            executingStep = true;
        }
        var stepResult = strategyService.step();
        synchronized (lockObject) {
            executingStep = false;
            lockObject.notifyAll();
        }
        return stepResult;
    }

    @GetMapping("/stop")
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

    @GetMapping("/startBacktest")
    public String startBacktest() {
        String validateTokenErrorMessage = backtestMarketService.validateToken();
        if (validateTokenErrorMessage != null) {
            return validateTokenErrorMessage;
        }

        strategyService.setMarketService(backtestMarketService);
        final int backDays = 365;
        LocalDateTime localDateTime = LocalDateTime.now().minus(backDays, ChronoUnit.DAYS);

        Instant fakeTime = localDateTime.toInstant(ZoneOffset.of("+03:00:00"));
        for (int i = 0; i < backDays; i++) {
            backtestMarketService.setFakeNow(fakeTime);
            strategyService.step();
            fakeTime = fakeTime.plus(1, ChronoUnit.DAYS);
        }
        return strategyService.totalAmountOfFunds(backtestMarketService.getPortfolio()).toString();
    }

    @GetMapping(value = "/step")
    public void step() {
        log.info("/step");
        strategyService.step();
    }

    @GetMapping(value = "/status")
    public String status() {
        log.info("/status");
        var marketService = strategyService.getMarketService();
        if (marketService == null) {
            return "Robot is not started.";
        }
        var sb = new StringBuilder();
        sb.append("<table>");
        sb.append("<tr><td>Robot is running");
        if (marketService instanceof SandboxMarketService) sb.append(" in sandbox");
        if (marketService instanceof BacktestMarketService) sb.append(" in backtest mode");
        sb.append("</td></tr>");

        var portfolio = marketService.getPortfolio();
        sb.append("<tr><td> Current result: ").append(strategyService.totalAmountOfFunds(portfolio)).append("</td></tr>");

        var moneyValue = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountCurrencies());
        sb.append("<tr><td> Free money: ").append(moneyValue).append("</td></tr>");

        List<PortfolioPosition> positionsList = portfolio.getPositionsList();
        sb.append("<tr><td> Number of open positions: ").append(positionsList.size()).append("</td></tr>");
        sb.append("</table>");
        if (positionsList.size() > 0) {
            sb.append("<table>");
            sb.append("<tr><th>Name</th><th>Count</th><th>Expected yield</th></tr>");
            for (PortfolioPosition portfolioPosition : positionsList) {
                String instrumentType = portfolioPosition.getInstrumentType();
                if (!"share".equals(instrumentType)) continue;
                var figi = portfolioPosition.getFigi();
                Share share = marketService.getShareByFigiSync(figi);
                var numberOfShares = portfolioPosition.getQuantityLots().getUnits() * share.getLot();
                String sYield = "unknown";
                if (portfolioPosition.hasExpectedYield())
                    sYield = MapperUtils.quotationToBigDecimal(portfolioPosition.getExpectedYield()).toString();
                sb.append("<tr><td>").append(share.getName()).append("</td><td>").append(numberOfShares).append("</td><td>").append(sYield).append("</td></tr>");
            }

            sb.append("</table>");
        }
        return sb.toString();
    }

    @GetMapping(value = "/config")
    public String config() {
        return generateConfigPage(null);
    }

    private String generateConfigPage(List<String> errors) {
        var sb = new StringBuilder();

        if (errors != null && errors.size() > 0) {
            sb.append("<h2>Errors:</h2>");
            for (String error : errors) {
                sb.append(error).append("<br>");
            }
            sb.append("<br>");
        }

        sb.append("<h2>Current values:</h2>");
        sb.append("<table>");
        sb.append("<tr><td>allowed-figis</td><td>").append(strategyService.getAllowedFigis()).append("</td></tr>");
        sb.append("<tr><td>min-dividend-yield</td><td>").append(strategyService.getMinDividendYield()).append("%</td></tr>");
        sb.append("<tr><td>sufficient-profit</td><td>").append(strategyService.getSufficientProfit()).append("%</td></tr>");
        sb.append("<tr><td style=\"width: 200px;\">max-position-percentage</td><td>").append(strategyService.getMaxPositionPercentage()).append("%</td></tr>");
        sb.append("</table><br>");
        sb.append("<h2>New values:</h2>");
        sb.append("<form action=\"config\" method=\"POST\">");
        sb.append("<table>");
        sb.append("<tr><td><label for=\"figis\"  style=\"width: 200px;\">Allowed figis:</label></td><td><input type=\"text\" id=\"figis\" name=\"figis\" style=\"width: 1000px;\"/></td></tr>");
        sb.append("<tr><td><label for=\"minDividendYield\">Minimal dividend yield:</label></td><td><input type=\"text\" id=\"minDividendYield\" name=\"minDividendYield\"/></td></tr>");
        sb.append("<tr><td><label for=\"sufficientProfit\">Sufficient profit:</label></td><td><input type=\"text\" id=\"sufficientProfit\" name=\"sufficientProfit\"/></td></tr>");
        sb.append("<tr><td><label for=\"maxPositionPercentage\">Maximum position percentage:</label></td><td><input type=\"text\" id=\"maxPositionPercentage\" name=\"maxPositionPercentage\"/></td></tr>");
        sb.append("</table><br>");
        sb.append("<input type=\"submit\" value=\"Submit\">");
        sb.append("</form>");
        return sb.toString();
    }

    @PostMapping(value = "/config")
    public String config(String figis, String minDividendYield, String sufficientProfit, String maxPositionPercentage) {
        var errors = new ArrayList<String>();
        checkAndSetFigis(figis, errors::add);
        checkAndSetValue(minDividendYield, "minimal dividend yield", d -> d >= 0, strategyService::setMinDividendYield, errors::add);
        checkAndSetValue(sufficientProfit, "sufficient profit", d -> d >= 0, strategyService::setSufficientProfit, errors::add);
        checkAndSetValue(maxPositionPercentage, "max position percentage", d -> d >= 0 && d <= 100, strategyService::setMaxPositionPercentage, errors::add);

        return generateConfigPage(errors);
    }

    private void checkAndSetValue(String doubleValue, String valueName, Function<Double,Boolean> predicate, Consumer<Double> setter, Consumer<String> reportError) {
        if (doubleValue != null && !doubleValue.isEmpty()) {
            try {
                double d = Double.parseDouble(doubleValue);
                if (predicate.apply(d))
                    setter.accept(d);
                else
                    reportError.accept("Value for " + valueName + " is not valid");
            } catch (Throwable t) {
                reportError.accept("Could not parse value for " + valueName);
            }
        }
    }

    private void checkAndSetFigis(String figis, Consumer<String> reportError) {
        if (figis == null || figis.isEmpty()) return;
        var validateTokenMessage = backtestMarketService.validateToken();
        if (validateTokenMessage != null) {
            reportError.accept("Cannot validate figis because validation of API token returned error: " + validateTokenMessage);
            return;
        }
        var messageForInvalidFigis = new StringBuilder();
        messageForInvalidFigis.append("Skipped some figis because could not find them in ").append(RealMarketService.MOEX_EXCHANGE).append(":");
        var validFigis = new StringBuilder();
        var hasInvalidFigis = false;
        for (var figi: figis.split("\\s+")) {
            try {
                Share share = backtestMarketService.getShareByFigiSync(figi);
                if (share.getExchange().equals(RealMarketService.MOEX_EXCHANGE))
                    validFigis.append(figi).append(' ');
                else
                {
                    log.info("Could find share for figi=" + figi);
                    messageForInvalidFigis.append(' ').append(figi);
                    hasInvalidFigis = true;
                }
            } catch (Throwable t) {
                log.info("Could find share for figi=" + figi);
                messageForInvalidFigis.append(' ').append(figi);
                hasInvalidFigis = true;
            }
        }
        var validFigisResult = validFigis.toString().trim();
        if (!validFigisResult.isEmpty())
            strategyService.setAllowedFigis(validFigisResult);
        if (hasInvalidFigis)
            reportError.accept(messageForInvalidFigis.toString());
    }
}
