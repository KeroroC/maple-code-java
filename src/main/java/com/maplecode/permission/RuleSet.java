package com.maplecode.permission;

import java.util.List;

public record RuleSet(List<Rule> rules) {
    public RuleSet { rules = List.copyOf(rules); }
}
