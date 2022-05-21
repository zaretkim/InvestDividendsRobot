package org.zaretkim.dividendsrobot.service;

import ru.tinkoff.piapi.contract.v1.*;

import java.time.Instant;
import java.util.List;

/**
 * Provides basic exchange operations required for pre-dividends strategy
 */
public interface MarketService {
    /**
     * Get current portfolio
     * @return current portfolio
     */
    PortfolioResponse getPortfolio();

    /**
     * Check if exchange is currently working
     * @return true if exchange is currently working
     */
    boolean isWorkingHours();

    /**
     * Get share by its figi
     * @param figi figi of the share
     * @return share for given figi
     */
    Share getShareByFigiSync(String figi);

    /**
     * Get current dividends information
     * @param figi figi of the share with requested dividends
     * @return current dividends for the figi
     */
    List<Dividend> getDividendsSync(String figi);

    /**
     * Get last price for the share with given figi
     * @param figi figi of the share for which price is request
     * @return last price for the share with given figi
     */
    LastPrice getLastPricesSync(String figi);

    /**
     * Get current time. Usually equals to "Instant.now()' except for services emulating back tests
     * @return current time
     */
    Instant now();

    /**
     * Create exchange order to sell share with given figi
     * @param figi figi of the share to sell
     * @param numberOfLots number of lots to sell
     * @return id of the created order
     */
    String sellMarket(String figi, int numberOfLots);

    /**
     * Create exchange order to buy share with given figi
     * @param figi figi of the share to buy
     * @param numberOfLots number of lots to buy
     * @return id of the created order
     */
    String buyMarket(String figi, int numberOfLots);

    /**
     * Get list of orders
     * @return list of orders
     */
    List<OrderState> getOrders();

    /**
     * Cancel order
     * @param orderId id of the order to cancel
     */
    void cancelOrder(String orderId);

    /**
     * Validates token for this service
     * @return null if token is valid or error message for invalid token
     */
    String validateToken();
}
