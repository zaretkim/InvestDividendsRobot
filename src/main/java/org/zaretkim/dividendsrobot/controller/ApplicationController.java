package org.zaretkim.dividendsrobot.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.zaretkim.dividendsrobot.service.*;
import ru.tinkoff.piapi.contract.v1.PortfolioPosition;
import ru.tinkoff.piapi.contract.v1.PortfolioResponse;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.utils.MapperUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * End points:
 * /start - starts robot in real exchange account
 * /startSandbox - start robot in sandbox account
 * /status - shows current portfolio for running robot in real or sandbox account
 * /startBacktest - runs robot on historical data for the last 365 days
 * /config - lists and configures paramaters for the strategy
 * /stop - stops robots started on real or sandbox accounts
 */
@RestController
@RequiredArgsConstructor
@Slf4j
public class ApplicationController {
    private final RobotRunner robotRunner;
    private final PreDividendsStrategyService preDividendsStrategyService;
    private final SandboxMarketService sandboxMarketService;
    private final RealMarketService realMarketService;
    private final BacktestMarketService backtestMarketService;

    @GetMapping("/startSandbox")
    public String startSandbox() {
        String validateTokenErrorMessage = sandboxMarketService.validateToken();
        if (validateTokenErrorMessage != null) {
            return validateTokenErrorMessage;
        }
        return robotRunner.startSandbox();
    }

    @GetMapping("/start")
    public String start(String force) {
        String validateTokenErrorMessage = realMarketService.validateToken();
        if (validateTokenErrorMessage != null) {
            return validateTokenErrorMessage;
        }
        if (force == null) {
            PortfolioResponse portfolio = realMarketService.getPortfolio();
            var hasOpenPosition = portfolio.getPositionsList().size() > 0;
            var cash = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountCurrencies());
            var sharesAmount = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountShares());
            var totalFunds = cash.add(sharesAmount);
            final int minimalRecommendedFunds = 10000;
            var messageBuilder = new StringBuilder();
            if (hasOpenPosition)
                messageBuilder.append("Warning: account has open positions which can be close by robot<br>");
            if (totalFunds.compareTo(BigDecimal.valueOf(minimalRecommendedFunds)) < 0)
                messageBuilder.append("Warning: account has not enough funds (").append(totalFunds).append("). It is recommended to have at least ").append(minimalRecommendedFunds).append("<br>");
            if (messageBuilder.length() > 0) {
                {
                    messageBuilder.append("Robot is not started<br>");
                    messageBuilder.append("To start robot anyway <a href=\"/start?force=true\">click here</a>");
                    return messageBuilder.toString();
                }
            }
        }

        return robotRunner.start();
    }

    @GetMapping("/stop")
    public String stopRunningRobot() {
        robotRunner.stopRunningRobot();
        return "Robot is stopped";
    }

    @GetMapping("/startBacktest")
    public String startBacktest() {
        String validateTokenErrorMessage = backtestMarketService.validateToken();
        if (validateTokenErrorMessage != null) {
            return validateTokenErrorMessage;
        }

        return robotRunner.startBacktest();
    }

    @GetMapping(value = "/status")
    public String status() {
        log.info("/status");
        var marketService = preDividendsStrategyService.getMarketService();
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
        sb.append("<tr><td> Current result: ").append(preDividendsStrategyService.totalAmountOfFunds(portfolio)).append("</td></tr>");

        var moneyValue = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountCurrencies());
        sb.append("<tr><td> Free money: ").append(moneyValue).append("</td></tr>");

        List<PortfolioPosition> positionsList = portfolio.getPositionsList();
        sb.append("<tr><td> Number of open positions: ").append(positionsList.size()).append("</td></tr>");
        if (portfolio.hasExpectedYield()) {
            var expectedYield = MapperUtils.quotationToBigDecimal(portfolio.getExpectedYield());
            sb.append("<tr><td> Expected yield: ").append(expectedYield).append("</td></tr>");
        }
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
        sb.append("<tr><td>allowed-figis</td><td>").append(preDividendsStrategyService.getAllowedFigis()).append("</td></tr>");
        sb.append("<tr><td>min-dividend-yield</td><td>").append(preDividendsStrategyService.getMinDividendYield()).append("%</td></tr>");
        sb.append("<tr><td>sufficient-profit</td><td>").append(preDividendsStrategyService.getSufficientProfit()).append("%</td></tr>");
        sb.append("<tr><td style=\"width: 200px;\">max-position-percentage</td><td>").append(preDividendsStrategyService.getMaxPositionPercentage()).append("%</td></tr>");
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
        checkAndSetValue(minDividendYield, "minimal dividend yield", d -> d >= 0, preDividendsStrategyService::setMinDividendYield, errors::add);
        checkAndSetValue(sufficientProfit, "sufficient profit", d -> d >= 0, preDividendsStrategyService::setSufficientProfit, errors::add);
        checkAndSetValue(maxPositionPercentage, "max position percentage", d -> d >= 0 && d <= 100, preDividendsStrategyService::setMaxPositionPercentage, errors::add);

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
            preDividendsStrategyService.setAllowedFigis(validFigisResult);
        if (hasInvalidFigis)
            reportError.accept(messageForInvalidFigis.toString());
    }
}
