package org.zaretkim.dividendsrobot.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import ru.tinkoff.piapi.contract.v1.*;
import ru.tinkoff.piapi.core.InvestApi;
import ru.tinkoff.piapi.core.SandboxService;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Implements MarketService for sandbox account
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SandboxMarketService extends MarketServiceBase{
    private InvestApi investApi;
    @Value("${app.config.sandbox-account}")
    protected String accountId;

    @Override
    protected InvestApi getInvestApi() {
        if (token == null || token.isBlank()){
            throw new IllegalArgumentException("Token is not valid. Please, check configuration in src/main/resources/application.yaml");
        }
        if (investApi == null) {
            investApi = InvestApi.createSandbox(token);
        }
        return investApi;
    }

    private String getAccountId() {
        if (!StringUtils.hasLength(accountId)) {
            log.info("no sandbox account was set. creating a new one");
            var sandboxService = getInvestApi().getSandboxService();
            accountId = sandboxService.openAccountSync();
            log.info("new sandbox account: {}", accountId);
        }
        return accountId;
    }
    @Override
    public PortfolioResponse getPortfolio() {
        InvestApi investApi = getInvestApi();
        SandboxService sandboxService = investApi.getSandboxService();
        return sandboxService.getPortfolioSync(getAccountId());
    }

    @Override
    public boolean isWorkingHours() {
        return true;
    }

    @Override
    public Instant now() {
        return Instant.now();
    }

    public void closeUnfinishedOrders() {
        SandboxService sandboxService = getInvestApi().getSandboxService();
        String account = getAccountId();
        for (OrderState orderState : sandboxService.getOrdersSync(account)) {
            if (orderState.getLotsExecuted() == orderState.getLotsRequested()) continue;
            log.info("Cancel order for {}", orderState.getFigi());
            sandboxService.cancelOrderSync(account, orderState.getOrderId());
        }
    }

    @Override
    public List<OrderState> getOrders() {
        SandboxService sandboxService = getInvestApi().getSandboxService();
        String account = getAccountId();
        return sandboxService.getOrdersSync(account);
    }

    @Override
    public void cancelOrder(String orderId) {
        SandboxService sandboxService = getInvestApi().getSandboxService();
        String account = getAccountId();
        sandboxService.cancelOrderSync(account, orderId);
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
        var accountId = getAccountId();
        getInvestApi().getSandboxService().postOrderSync(figi, numberOfLots, Quotation.getDefaultInstance(), orderDirection, accountId, OrderType.ORDER_TYPE_MARKET, orderId);
        return orderId;
    }
}
