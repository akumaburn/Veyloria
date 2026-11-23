package com.eonmux.chatllm.graphql.scalars;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@ApplicationScoped
public class GsonBeanProducer {

    @Produces
    @ApplicationScoped
    public Gson gson() {
        return new GsonBuilder()
                .registerTypeAdapterFactory(new Time4JGsonTypeAdapterFactory())
                .create();
    }
}
