package com.example.tokenpatterns.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.List;
import java.util.Map;

public record PatternDefinition(
        String id,
        int order,
        String name,
        String icon,
        String savingsRange,
        String headline,
        String description,
        String mechanism,
        String bestFor,
        String watchOut,
        String primaryMetric,
        String sampleInput,
        List<FlowNode> nodes,
        List<FlowEdge> edges,
        @JsonIgnore Map<String, String> agentNodes) {

    public PatternDefinition {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
        agentNodes = Map.copyOf(agentNodes);
    }

    public String nodeFor(String agentName) {
        String exact = agentNodes.get(agentName);
        if (exact != null) {
            return exact;
        }
        int instanceSuffix = agentName.lastIndexOf('_');
        if (instanceSuffix > 0 && agentName.substring(instanceSuffix + 1).chars().allMatch(Character::isDigit)) {
            return agentNodes.getOrDefault(agentName.substring(0, instanceSuffix), "");
        }
        return "";
    }

    public record FlowNode(String id, String label, String caption, String kind, int x, int y) {
    }

    public record FlowEdge(String from, String to, String label, boolean dashed) {
    }
}