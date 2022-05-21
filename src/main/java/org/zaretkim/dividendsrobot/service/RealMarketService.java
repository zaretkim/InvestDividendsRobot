package org.zaretkim.dividendsrobot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.OrdersService;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static ru.tinkoff.piapi.core.utils.Helpers.unaryCall;

/**
 * MarketService implementation for real exchange account
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RealMarketService extends MarketServiceBase {

    public static final String MOEX_EXCHANGE = "MOEX";
    private InvestApi investApi;
    @Value("${app.config.appname}")
    private String appname;
    @Value("${app.config.market-account}")
    protected String accountId;
    private OperationsServiceGrpc.OperationsServiceBlockingStub operationsBlockingStub;

    private OperationsServiceGrpc.OperationsServiceBlockingStub getOperationsBlocking() {
        if (operationsBlockingStub == null) {
            var channel = getInvestApi().getChannel();
            operationsBlockingStub = OperationsServiceGrpc.newBlockingStub(channel);
        }
        return operationsBlockingStub;
    }

    @Override
    public PortfolioResponse getPortfolio() {
        var request = PortfolioRequest.newBuilder().setAccountId(accountId).build();
        return unaryCall(() -> operationsBlockingStub.getPortfolio(request));
    }

    @Override
    public boolean isWorkingHours() {
        var now = now();
        TradingSchedule schedule = getInvestApi().getInstrumentsService().getTradingScheduleSync(MOEX_EXCHANGE, now, now.plus(1, ChronoUnit.MINUTES));
        var nowSeconds = now.getEpochSecond();
        for (TradingDay tradingDay : schedule.getDaysList()) {
            if (tradingDay.getStartTime().getSeconds() > nowSeconds)
                return false;
            if (nowSeconds < tradingDay.getEndTime().getSeconds())
                return tradingDay.getIsTradingDay();
        }

        return false;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    @Override
    public List<OrderState> getOrders() {
        OrdersService ordersService = getInvestApi().getOrdersService();
        return ordersService.getOrdersSync(accountId);
    }

    @Override
    public void cancelOrder(String orderId) {
        OrdersService ordersService = getInvestApi().getOrdersService();
        ordersService.cancelOrderSync(accountId, orderId);
    }

    public String sellMarket(String figi, int numberOfLots) {
        log.info("sell {} lots={}", figi, numberOfLots);
        return postOrderSync(figi, numberOfLots, OrderDirection.ORDER_DIRECTION_SELL);
    }

    public String buyMarket(String figi, int numberOfLots) {
        log.info("buy {} lots={}", figi, numberOfLots);
        return postOrderSync(figi, numberOfLots, OrderDirection.ORDER_DIRECTION_BUY);
    }

    private String postOrderSync(String figi, int numberOfLots, OrderDirection orderDirection) {
        var orderId = UUID.randomUUID().toString();
        getInvestApi().getOrdersService().postOrderSync(figi, numberOfLots, Quotation.getDefaultInstance(), orderDirection, accountId, OrderType.ORDER_TYPE_MARKET, orderId);
        return orderId;
    }

    @Override
    protected InvestApi getInvestApi() {
        if (token == null || token.isBlank()){
            throw new IllegalArgumentException("Token is not valid. Please, check configuration in src/main/resources/application.yaml");
        }
        if (investApi == null) {
            investApi = InvestApi.create(token, appname);
        }
        return investApi;
    }

    @Override
    public String validateToken() {
        var superValidation = super.validateToken();
        if (superValidation != null)
            return superValidation;
        var investApi = getInvestApi();
        if (investApi.isReadonlyMode())
            return "Token is not valid for real market. It is readonly.";
        return null;
    }
}
