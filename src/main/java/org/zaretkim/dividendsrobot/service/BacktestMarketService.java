package org.zaretkim.dividendsrobot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.MarketDataService;
import ru.tinkoff.piapi.core.utils.MapperUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class BacktestMarketService extends MarketServiceBase {
    private static final String CONTROL_FIGI = "BBG004730RP0"; // Gazprom figi
    private InvestApi investApi;
    private Instant fakeNow;
    private final HashMap<String, PortfolioPosition> portfolioPositions = new HashMap<>();
    private final HashMap<String, List<Dividend>> historicalDividends = new HashMap<>();
    private final HashMap<String, Share> sharesMap = new HashMap<>();
    private final HashMap<String, List<HistoricCandle>> historicalCandles = new HashMap<>();
    private BigDecimal cash = BigDecimal.valueOf(100000);

    @Override
    public PortfolioResponse getPortfolio() {
        BigDecimal totalSharesCount = BigDecimal.ZERO;
        for (PortfolioPosition position: portfolioPositions.values()) {
            var figi = position.getFigi();
            var price = getLastPricesSync(figi);
            var quantity = position.getQuantity();
            var amount = MapperUtils.quotationToBigDecimal(price.getPrice()).multiply(BigDecimal.valueOf(quantity.getUnits()));
            totalSharesCount = totalSharesCount.add(amount);
        }
        return PortfolioResponse.newBuilder().
                addAllPositions(portfolioPositions.values()).
                setTotalAmountShares(MapperUtils.bigDecimalToMoneyValue(totalSharesCount)).
                setTotalAmountCurrencies(MapperUtils.bigDecimalToMoneyValue(cash)).
                build();
    }

    @Override
    public boolean isWorkingHours() {
        return getLastPricesSync(CONTROL_FIGI) != null;
    }

    @Override
    public Instant now() {
        return fakeNow;
    }

    public void setFakeNow(Instant fakeNow) {
        this.fakeNow = fakeNow;
    }

    @Override
    public LastPrice getLastPricesSync(String figi) {
        var figiHistoricalCandles = historicalCandles.get(figi);
        if (figiHistoricalCandles == null) {
            MarketDataService marketDataService = getInvestApi().getMarketDataService();
            figiHistoricalCandles = marketDataService.getCandlesSync(figi, fakeNow, Instant.now(), CandleInterval.CANDLE_INTERVAL_DAY);
            historicalCandles.put(figi, figiHistoricalCandles);
        }

        for (HistoricCandle candle : figiHistoricalCandles) {
            if (fakeNow.getEpochSecond() - candle.getTime().getSeconds() < 24 * 60 * 60) {
                BigDecimal high = MapperUtils.quotationToBigDecimal(candle.getHigh());
                BigDecimal low = MapperUtils.quotationToBigDecimal(candle.getLow());
                BigDecimal medium = high.add(low).divide(BigDecimal.valueOf(2), RoundingMode.HALF_DOWN);
                return LastPrice.newBuilder().setFigi(figi).setPrice(MapperUtils.bigDecimalToQuotation(medium)).build();
            }
        }
        return null;
    }

    @Override
    public String sellMarket(String figi, int numberOfLots) {
        log.info("Sell {} lots={} price={}", figi, numberOfLots, getLastPricesSync(figi));
        var position = portfolioPositions.remove(figi);
        if (position == null) {
            throw new RuntimeException("Shorts are not allowed");
        }
        var price = totalPrice(figi, numberOfLots);
        cash = cash.add(price);
        return "fake sell order id";
    }

    private BigDecimal totalPrice(String figi, int numberOfLots) {
        var lastPrice = getLastPricesSync(figi);
        var share = getShareByFigiSync(figi);
        var numberOfShares = numberOfLots * share.getLot();
        return MapperUtils.quotationToBigDecimal(lastPrice.getPrice()).multiply(BigDecimal.valueOf(numberOfShares));
    }

    @Override
    public String buyMarket(String figi, int numberOfLots) {
        log.info("Buy {} lots={} price={}", figi, numberOfLots, getLastPricesSync(figi));
        var lastPrice = getLastPricesSync(figi);
        var share = getShareByFigiSync(figi);
        var numberOfShares = numberOfLots * share.getLot();
        var price = MapperUtils.quotationToBigDecimal(lastPrice.getPrice()).multiply(BigDecimal.valueOf(numberOfShares));
        if (price.compareTo(cash) > 0) {
            throw new RuntimeException("Not enough cash");
        }
        if (portfolioPositions.containsKey(figi)) {
            throw new RuntimeException("Cannot buy new shares to existing position");
        }
        var newPosition = PortfolioPosition.newBuilder().
                setFigi(figi).
                setInstrumentType("share").
                setQuantity(MapperUtils.bigDecimalToQuotation(BigDecimal.valueOf(numberOfShares))).
                setQuantityLots(MapperUtils.bigDecimalToQuotation(BigDecimal.valueOf(numberOfLots))).
                build();
        portfolioPositions.put(figi, newPosition);
        cash = cash.subtract(price);
        return "fake buy order id";
    }

    @Override
    public List<OrderState> getOrders() {
        return Collections.emptyList();
    }

    @Override
    public void cancelOrder(String orderId) {
    }

    @Override
    public List<Dividend> getDividendsSync(String figi) {
        List<Dividend> dividendList = historicalDividends.get(figi);
        if (dividendList == null) {
            Instant from = now();
            Instant to = Instant.now();
            InvestApi investApi = getInvestApi();
            InstrumentsService instrumentsService = investApi.getInstrumentsService();
            dividendList = instrumentsService.getDividendsSync(figi, from, to);
            historicalDividends.put(figi, dividendList);
        }
        List<Dividend> result = new ArrayList<>();
        long fakeNowEpochSecond = fakeNow.getEpochSecond();
        for (Dividend div: dividendList) {
            if (div.getDeclaredDate().getSeconds() < fakeNowEpochSecond && fakeNowEpochSecond < div.getLastBuyDate().getSeconds())
            {
                result.add(div);
            }
        }
        return result;
    }

    @Override
    public Share getShareByFigiSync(String figi) {
        Share share = sharesMap.get(figi);
        if (share == null) {
            share= getInvestApi().getInstrumentsService().getShareByFigiSync(figi);
            sharesMap.put(figi, share);
        }
        return share;
    }

    @Override
    protected InvestApi getInvestApi() {
        if (token == null || token.isBlank()){
            throw new IllegalArgumentException("Token is not valid, please check configuration in src/main/resources/application.yaml");
        }
        if (investApi == null) {
            investApi = InvestApi.createSandbox(token);
        }
        return investApi;
    }
}
