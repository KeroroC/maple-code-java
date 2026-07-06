package com.maplecode.permission;

public record Decision(Verdict verdict, String reason) {
    public enum Verdict { ALLOW, DENY }
    public static Decision allow(String why) { return new Decision(Verdict.ALLOW, why); }
    public static Decision deny(String why)  { return new Decision(Verdict.DENY, why); }
}
