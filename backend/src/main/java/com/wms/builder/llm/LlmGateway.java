package com.wms.builder.llm;

/**
 * The one way the builder talks to a language model (AR-5). Providers are plugged in behind it; nothing else in the
 * builder uses a provider's own library.
 */
public interface LlmGateway {

    /** Which model answers, for the record. */
    String model();

    /**
     * Sends the conversation and waits for the answer.
     *
     * @throws LlmException when the provider can't be reached or refuses the call itself
     */
    Llm.Reply send(Llm.Request request);

    /** Something went wrong talking to the model, said so a person can act on it. */
    class LlmException extends RuntimeException {
        public LlmException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
