package org.zaretkim.dividendsrobot.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import ru.tinkoff.piapi.contract.v1.Dividend;
import ru.tinkoff.piapi.contract.v1.LastPrice;
import ru.tinkoff.piapi.contract.v1.Share;
import ru.tinkoff.piapi.core.InstrumentsService;
import ru.tinkoff.piapi.core.InvestApi;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@Slf4j
public abstract class MarketServiceBase implements MarketService {
    @Value("${app.config.token}")
    protected String token;


    protected abstract InvestApi getInvestApi();
    @Override
    public List<Dividend> getDividendsSync(String figi) {
        Instant from = now();
        Instant to = from.plus(30, ChronoUnit.DAYS);
        InvestApi investApi = getInvestApi();
        InstrumentsService instrumentsService = investApi.getInstrumentsService();
        return instrumentsService.getDividendsSync(figi, from, to);
    }

    @Override
    public Share getShareByFigiSync(String figi) {
        return getInvestApi().getInstrumentsService().getShareByFigiSync(figi);
    }

    @Override
    public LastPrice getLastPricesSync(String figi) {
        List<LastPrice> lastPrices = getInvestApi().getMarketDataService().getLastPricesSync(List.of(figi));
        if (lastPrices.size() == 0) {
            log.info("Could not get last prices for " + figi);
            return null;
        }
        return lastPrices.get(0);
    }

    @Override
    public String validateToken() {
        if (token == null || token.isEmpty())
            return "Token is not configured. Please, configure it in src/main/resources/application.yaml";

        try {
            getInvestApi();
        } catch (Throwable t) {
            log.info("Could not create investApi for backtest", t);
            return "Token is not valid";
        }
        return null;
    }
}
