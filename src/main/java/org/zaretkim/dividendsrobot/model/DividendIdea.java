package org.zaretkim.dividendsrobot.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;

@Data
@Slf4j
@AllArgsConstructor
public class DividendIdea {
    private String figi;
    private String ticker;
    private BigDecimal dividendYield;
    private BigDecimal currentPrice;
    private BigDecimal dividendNet;
}
