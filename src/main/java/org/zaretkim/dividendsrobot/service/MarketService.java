package org.zaretkim.dividendsrobot.service;

import com.google.protobuf.Timestamp;
import ru.tinkoff.piapi.contract.v1.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface MarketService {
    PortfolioResponse getPortfolio();
    boolean isWorkingHours();
    Share getShareByFigiSync(String figi);
    List<Dividend> getDividendsSync(String figi);
    LastPrice getLastPricesSync(String figi);
    Instant now();
    String sellMarket(String figi, int numberOfLots);
    String buyMarket(String figi, int numberOfLots);
    List<OrderState> getOrders();
    void cancelOrder(String orderId);
    String validateToken();
}
