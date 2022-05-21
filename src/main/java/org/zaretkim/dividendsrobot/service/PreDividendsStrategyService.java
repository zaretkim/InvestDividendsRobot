package org.zaretkim.dividendsrobot.service;

import com.google.protobuf.Timestamp;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.zaretkim.dividendsrobot.model.DividendIdea;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.utils.MapperUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PreDividend strategy implementation. On every @step run finds shares which hava actual declared dividends
 * with yield higher than @minDividendYield and opens positions for them. Close positions when they have
 * at least @sufficientProfit profit or last buy date for the dividends is reached. @step is executed once a day
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PreDividendsStrategyService {
    @Setter
    private MarketService marketService;
    @Value("${app.config.sufficient-profit}")
    private double sufficientProfit;

    @Value("${app.config.max-position-percentage}")
    private double maxPositionPercentage;

    @Value("${app.config.allowed-figis}")
    private String allowedFigis;

    @Value("${app.config.min-dividend-yield}")
    private double minDividendYield;

    public double getSufficientProfit() {
        return sufficientProfit;
    }

    public void setSufficientProfit(double sufficientProfit) {
        this.sufficientProfit = sufficientProfit;
    }

    public double getMaxPositionPercentage() {
        return maxPositionPercentage;
    }

    public void setMaxPositionPercentage(double maxPositionPercentage) {
        this.maxPositionPercentage = maxPositionPercentage;
    }

    public String getAllowedFigis() {
        return allowedFigis;
    }

    public void setAllowedFigis(String allowedFigis) {
        this.allowedFigis = allowedFigis;
    }

    public double getMinDividendYield() {
        return minDividendYield;
    }

    public void setMinDividendYield(double minDividendYield) {
        this.minDividendYield = minDividendYield;
    }

    /**
     * Executes next step for the strategy
     * @return true if step is successfully executed or false if any error happened
     */
    public boolean step() {

        try {
            if (!marketService.isWorkingHours()) {
                log.info("Out of working hours");
                return true;
            }
            PortfolioResponse portfolio = marketService.getPortfolio();
            Set<String> dividendsFigis = findDividendsIdeas().stream().map(DividendIdea::getFigi).collect(Collectors.toSet());
            closePendingOrders();
            closeOutdatedPositions(portfolio, dividendsFigis);
            openNewPositions(portfolio, dividendsFigis);
            return true;
        } catch (Throwable t) {
            log.error(t.getMessage(), t);
            return false;
        }
    }

    public List<DividendIdea> findDividendsIdeas() {
        Timestamp now = Timestamp.newBuilder().setSeconds(marketService.now().getEpochSecond()).build();

        var minDividendYieldValue = BigDecimal.valueOf(minDividendYield).divide(BigDecimal.valueOf(100), RoundingMode.HALF_DOWN);
        var ideas = new ArrayList<DividendIdea>();
        for (var figi: allowedFigis.split("\\s+")) {
            try {
                List<Dividend> dividends = marketService.getDividendsSync(figi);
                if (dividends.size() == 0) continue;
                var dividend = dividends.get(0);
                if (!dividend.hasLastBuyDate()) continue;
                var lastBuyDate = dividend.getLastBuyDate();
                if (lastBuyDate.getSeconds() < now.getSeconds()) continue;
                MoneyValue dividendNet = dividend.getDividendNet();
                Share share = marketService.getShareByFigiSync(figi);
                if (!dividendNet.getCurrency().equals(share.getCurrency())) continue;
                var lastPrice = marketService.getLastPricesSync(figi);
                if (!lastPrice.hasPrice()) continue;
                Quotation priceQuotation = lastPrice.getPrice();
                var price = MapperUtils.quotationToBigDecimal(priceQuotation);
                var dividendValue = MapperUtils.moneyValueToBigDecimal(dividendNet);
                var dividendYield = dividendValue.divide(price, RoundingMode.HALF_UP);
                if (dividendYield.compareTo(minDividendYieldValue) < 0) continue;
                var idea = new DividendIdea(figi, share.getTicker(), dividendYield, price, dividendValue);
                ideas.add(idea);
            } catch (Exception e) {
                log.error("Failed to calculate idea for figi=" + figi, e);
            }
        }
        return ideas;
    }

    /**
     * Creates "buy" orders for figis from @dividendsFigis
     * @param portfolio current portfolio
     * @param dividendsFigis figis to buy
     */
    private void openNewPositions(PortfolioResponse portfolio, Set<String> dividendsFigis) {
        HashSet<String> figisToOpen = new HashSet<>(dividendsFigis);
        for (PortfolioPosition portfolioPosition : portfolio.getPositionsList()) {
            figisToOpen.remove(portfolioPosition.getFigi());
        }
        BigDecimal totalAmountOfFunds = totalAmountOfFunds(portfolio);
        BigDecimal maxAmountForOnePosition = totalAmountOfFunds.multiply(BigDecimal.valueOf(maxPositionPercentage / 100));
        BigDecimal availableCash = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountCurrencies());
        for (String figi : figisToOpen) {
            Share share = marketService.getShareByFigiSync(figi);
            LastPrice lastPrice = marketService.getLastPricesSync(figi);
            BigDecimal lotPrice = MapperUtils.quotationToBigDecimal(lastPrice.getPrice()).multiply(BigDecimal.valueOf(share.getLot()));
            int numberOfLots = maxAmountForOnePosition.divide(lotPrice, RoundingMode.DOWN).intValue();
            var totalPositionPrice = lotPrice.multiply(BigDecimal.valueOf(numberOfLots));
            while (totalPositionPrice.compareTo(availableCash.multiply(BigDecimal.valueOf(0.95))) >= 0) {
                totalPositionPrice = totalPositionPrice.subtract(lotPrice);
                numberOfLots--;
            }
            if (numberOfLots > 0)
            {
                marketService.buyMarket(figi, numberOfLots);
                availableCash = availableCash.subtract(totalPositionPrice);
            }
        }

    }

    public BigDecimal totalAmountOfFunds(PortfolioResponse portfolio) {
        var currencies = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountCurrencies());
        var shares = MapperUtils.moneyValueToBigDecimal(portfolio.getTotalAmountShares());
        var total = currencies.add(shares);
        log.info("total: {}", total);
        return total;
    }

    private void closeOutdatedPositions(PortfolioResponse portfolio, Set<String> dividendIdeaFigis) {
        for (PortfolioPosition portfolioPosition : portfolio.getPositionsList()) {
            var instrumentType = portfolioPosition.getInstrumentType();
            if (!"share".equals(instrumentType)) continue;
            if (dividendIdeaFigis.contains(portfolioPosition.getFigi())) continue;
            try {
                BigDecimal expectedYield = MapperUtils.quotationToBigDecimal(portfolioPosition.getExpectedYield());
                if (expectedYield.compareTo(BigDecimal.valueOf(sufficientProfit)) <= 0 && hasTimeBeforeLastBuyDate(portfolioPosition.getFigi()))
                    continue;

                marketService.sellMarket(portfolioPosition.getFigi(), (int) portfolioPosition.getQuantityLots().getUnits());
            } catch (Throwable e) {
                log.info("Failed to process {}, error: {}", portfolioPosition.getFigi(), e.getMessage());
            }
        }

    }

    public boolean hasTimeBeforeLastBuyDate(String figi) {
        List<Dividend> dividendList = marketService.getDividendsSync(figi);
        if (dividendList.size() == 0) return false;
        Dividend dividend = dividendList.get(0);
        Instant lastBuyDate = Instant.ofEpochSecond(dividend.getLastBuyDate().getSeconds()).truncatedTo(ChronoUnit.DAYS);
        return lastBuyDate.compareTo(marketService.now()) > 0;
    }

    private void closePendingOrders() {
        for (OrderState orderState : marketService.getOrders()) {
            if (orderState.getLotsExecuted() == orderState.getLotsRequested()) continue;
            log.info("Cancel order for {}", orderState.getFigi());
            marketService.cancelOrder(orderState.getOrderId());
        }
    }

    public MarketService getMarketService() {
        return marketService;
    }
}
