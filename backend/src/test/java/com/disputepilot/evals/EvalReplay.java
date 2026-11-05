package com.disputepilot.evals;

import com.disputepilot.agent.DisputeAgent;
import com.disputepilot.agent.DraftValidator;
import com.disputepilot.agent.EvidenceAssembler;
import com.disputepilot.agent.RecordedLlmClient;
import com.disputepilot.merchant.MerchantDataPort;
import com.disputepilot.merchant.SeededMerchantData;
import java.io.IOException;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Replays one eval case's committed recording through the real agent and validator. No network, no model. */
final class EvalReplay {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Path RECORDINGS =
            Path.of(System.getProperty("user.dir")).getParent().resolve("evals/recordings");

    record Replay(EvalCase evalCase, EvidenceAssembler.Assembled assembled, DisputeAgent.AgentOutcome outcome) {
    }

    private EvalReplay() {
    }

    static Replay replay(String caseName) throws IOException {
        EvalRunnerTest runner = new EvalRunnerTest();
        EvalCase c = runner.loadCases().stream().filter(x -> x.name().equals(caseName)).findFirst().orElseThrow();
        MerchantDataPort merchant = c.merchant() == null || c.merchant().isNull()
                ? new SeededMerchantData() : new InMemoryMerchantData(c.merchant(), JSON);
        EvidenceAssembler.Assembled assembled = new EvidenceAssembler(merchant).assemble(runner.disputeOf(c));
        DisputeAgent agent = new DisputeAgent(RecordedLlmClient.fromDirectory(RECORDINGS), new DraftValidator(), JSON);
        return new Replay(c, assembled, agent.draft(c.caseKey(), assembled));
    }
}
