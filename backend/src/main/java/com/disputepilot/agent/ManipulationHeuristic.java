package com.disputepilot.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * A cheap heuristic for buyer messages that talk to the AI or the reviewer instead of describing the dispute
 * ("ignore previous instructions", "you must accept", role-play, fake staff notes, hidden text). It never
 * decides anything by itself: a hit puts a visible warning on the draft and makes an ACCEPT need an explicit
 * override. Buyer text stays untrusted data either way.
 */
public final class ManipulationHeuristic {

    private record Rule(String label, Pattern pattern) {
        Rule(String label, String regex) {
            this(label, Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE | Pattern.DOTALL));
        }
    }

    private static final List<Rule> RULES = List.of(
            new Rule("tells the AI to ignore its instructions",
                    "\\b(ignore|disregard|forget|override)\\b[^.\\n]{0,40}\\b(previous|prior|above|earlier|all|your|any|system)\\b[^.\\n]{0,30}\\b(instructions?|rules|prompt|guidelines)\\b"),
            new Rule("tells the AI or reviewer what it must decide",
                    "\\byou\\s+(must|have to|need to|are required to|will)\\s+(accept|approve|refund|rule|side|recommend|set|mark|cite)\\b"),
            new Rule("tries to set the recommendation",
                    "\\b(set|change|switch|make)\\s+(the\\s+)?recommendation\\b|\\brecommendation\\s*(to|=|:)\\s*(accept|refund|offer)\\b"),
            new Rule("role-play or persona change",
                    "\\b(you are now|act as|pretend (to be|you)|role-?play|from now on you|new instructions?)\\b"),
            new Rule("speaks to the assistant, the model or the reviewer",
                    "(^|\\n)\\s*(assistant|system|ai|developer|reviewer)\\s*[:,]|\\b(dear|attention|note to)\\s+(ai|assistant|model|reviewer|claude|chatgpt|llm)\\b|\\bsystem prompt\\b"),
            new Rule("claims to be an internal or pre-approved note",
                    "\\b(pre-?approved|already approved by|internal note|staff only|merchant approval)\\b|={3,}[^=\\n]{0,60}(note|instruction|system)[^=\\n]{0,60}={3,}"),
            new Rule("contains hidden text",
                    "[\\u200B-\\u200F\\u2060\\uFEFF\\u202A-\\u202E]|<!--|display\\s*:\\s*none|font-size\\s*:\\s*0|<\\s*(script|system|instructions?)\\b"));

    private ManipulationHeuristic() {
    }

    /** Why each buyer-written message looks like it is aimed at the AI or the reviewer; empty when none do. */
    public static List<String> findings(CaseFile caseFile) {
        List<String> out = new ArrayList<>();
        caseFile.customerMessages().stream()
                .filter(m -> "BUYER".equalsIgnoreCase(m.from()))
                .forEach(m -> scan(m.citationId(), m.body(), out));
        caseFile.disputeMessages().stream()
                .filter(m -> "BUYER".equalsIgnoreCase(m.postedBy()))
                .forEach(m -> scan(m.citationId(), m.content(), out));
        return out;
    }

    public static void scan(String citationId, String text, List<String> out) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (Rule rule : RULES) {
            if (rule.pattern().matcher(text).find()) {
                out.add(citationId + " " + rule.label());
            }
        }
    }
}
