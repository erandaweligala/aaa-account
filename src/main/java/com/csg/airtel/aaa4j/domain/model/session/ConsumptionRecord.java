package com.csg.airtel.aaa4j.domain.model.session;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@AllArgsConstructor
@NoArgsConstructor
public class ConsumptionRecord {
    private LocalDateTime timestamp;
    private Long bytesConsumed;
}
