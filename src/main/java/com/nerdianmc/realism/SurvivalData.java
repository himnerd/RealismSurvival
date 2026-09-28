package com.nerdianmc.realism;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public final class SurvivalData {
    private double hydration = 100.0;
    private double fatigue;
    private double temperature = 37.0;
    private long stimulantUntil;
    private long shortRestUntil;
    private long lastWorldTick = -1L;
    private int fireRestChecks;
}