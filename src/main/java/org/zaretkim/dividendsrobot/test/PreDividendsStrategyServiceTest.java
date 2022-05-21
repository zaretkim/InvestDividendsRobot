package org.zaretkim.dividendsrobot.test;

import org.junit.jupiter.api.Test;
import org.zaretkim.dividendsrobot.service.PreDividendsStrategyService;

import java.time.temporal.ChronoUnit;

public class PreDividendsStrategyServiceTest {
    private static final String TEST_FIGI = "TEST_FIGI";
    private static final int INITIAL_CASH = 1000000;
    private static final double MAX_POSITION_PERCENTAGE = 20;
    private static final double SUFFICIENT_PROFIT = 3;
    private static final double MIN_DIVIDEND_YIELD = 5;

    @Test
    void testNoOperationOutOfWorkingHours() {
        var testMarketService = new TestMarketService();
        var strategyService = createStrategyWithDefaultConfiguration(testMarketService);

        testMarketService.setWorkingHours(false);
        var now = testMarketService.now();
        testMarketService.addDividend(TEST_FIGI, now.plus(1, ChronoUnit.DAYS), 10);
        testMarketService.setLastPrice(TEST_FIGI, 100);

        strategyService.step();
    }

    @Test
    void testPositionIsOpenWhenDividendIdeaExists() {
        var testMarketService = new TestMarketService();
        var strategyService = createStrategyWithDefaultConfiguration(testMarketService);

        var now = testMarketService.now();
        testMarketService.addDividend(TEST_FIGI, now.plus(1, ChronoUnit.DAYS), 10);
        int price = 100;
        testMarketService.setLastPrice(TEST_FIGI, price);
        int expectedLots = (int) (INITIAL_CASH * MAX_POSITION_PERCENTAGE / 100 / price / TestMarketService.LOT);
        testMarketService.expectedBuy(TEST_FIGI, expectedLots);

        strategyService.step();

        testMarketService.assertAllSellsAndBuysAreDone();
    }

    @Test
    void testPositionIsClosedAfterLastBuyDate() {
        var testMarketService = new TestMarketService();
        var strategyService = createStrategyWithDefaultConfiguration(testMarketService);

        var now = testMarketService.now();
        testMarketService.addDividend(TEST_FIGI, now.minus(1, ChronoUnit.DAYS), 10);
        testMarketService.setLastPrice(TEST_FIGI, 100);
        var numberOfLots = 2;
        testMarketService.addPosition(TEST_FIGI, numberOfLots * TestMarketService.LOT, 0.07);
        testMarketService.expectedSell(TEST_FIGI, numberOfLots);

        strategyService.step();

        testMarketService.assertAllSellsAndBuysAreDone();
    }

    @Test
    void testPositionIsClosedAfterYieldIsEnough() {
        var testMarketService = new TestMarketService();
        var strategyService = createStrategyWithDefaultConfiguration(testMarketService);

        testMarketService.setLastPrice(TEST_FIGI, 100);
        var numberOfLots = 2;
        testMarketService.addPosition(TEST_FIGI, numberOfLots * TestMarketService.LOT, MIN_DIVIDEND_YIELD);
        testMarketService.expectedSell(TEST_FIGI, numberOfLots);

        strategyService.step();

        testMarketService.assertAllSellsAndBuysAreDone();
    }

    private PreDividendsStrategyService createStrategyWithDefaultConfiguration(TestMarketService marketService) {
        var strategyService = new PreDividendsStrategyService();
        strategyService.setMarketService(marketService);
        marketService.setCash(INITIAL_CASH);
        strategyService.setAllowedFigis(TEST_FIGI);
        strategyService.setMaxPositionPercentage(MAX_POSITION_PERCENTAGE);
        strategyService.setMinDividendYield(MIN_DIVIDEND_YIELD);
        strategyService.setSufficientProfit(SUFFICIENT_PROFIT);
        return strategyService;
    }
}
