package com.eonmux.chatllm.agents;

import com.eonmux.chatllm.config.AppConfig;
import com.eonmux.chatllm.services.AgentModelConfigService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.json.JsonObject;
import io.vertx.mutiny.core.Vertx;
import io.vertx.mutiny.sqlclient.Pool;

/** One resident utterance, after the already saved arrival. Never a new party round. */
public class ResidentGreetingAgent extends BaseAgent {
    public ResidentGreetingAgent(Vertx vertx, Pool pool, AppConfig config, AgentModelConfigService models) {
        super("resident_greeting",vertx,pool,config,models);
    }
    @Override protected Uni<AgentResult> execute(AgentContext context,String model) {
        JsonObject resident=context.getArrivalResident();
        return callLlmProse("Write only this local resident's brief greeting in their own voice: " + resident.getString("systemPrompt")
            + " Do not move anyone, describe the party's response, decide transactions, invent a crisis, or write narrator prose."
            + " The arrival already happened; use one to three sentences of dialogue, without labels or metadata.",
            "Saved arrival: "+context.getNarratorOutput()+"\nResident: "+resident.getString("name")
                +"\nEstablished conversation: "+context.getRecentMessages(),0.6f,300,model)
            .map(response -> AgentResult.builder().status("success").content(extractContent(response)).build());
    }
}
