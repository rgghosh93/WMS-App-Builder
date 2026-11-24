-- The App Builder: change requests people describe in plain language, and the conversation with the agent that builds them.
CREATE SEQUENCE builder_change_seq START WITH 1;

CREATE TABLE builder_changes (
    id             UUID          NOT NULL,
    number         VARCHAR(20)   NOT NULL,
    title          VARCHAR(200)  NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    -- The agent is working on it now.
    working        BOOLEAN       DEFAULT FALSE NOT NULL,
    -- Someone asked the agent to stop; it does after the step it is on.
    stop_requested BOOLEAN       DEFAULT FALSE NOT NULL,
    branch         VARCHAR(100)  NOT NULL,
    requested_by   VARCHAR(40)   NOT NULL,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    input_tokens   BIGINT        DEFAULT 0 NOT NULL,
    output_tokens  BIGINT        DEFAULT 0 NOT NULL,
    last_problem   VARCHAR(1000),
    CONSTRAINT pk_builder_changes PRIMARY KEY (id),
    CONSTRAINT uq_builder_changes_number UNIQUE (number),
    CONSTRAINT ck_builder_changes_status CHECK (status IN ('DRAFTING', 'IN_REVIEW', 'RELEASED', 'DISCARDED'))
);

-- Each turn of the conversation, in order: what someone asked, what the agent said and did, and what came of it.
CREATE TABLE builder_entries (
    id             UUID          NOT NULL,
    change_id      UUID          NOT NULL,
    seq            INTEGER       NOT NULL,
    role           VARCHAR(20)   NOT NULL,
    -- The turn's blocks (text, tool calls, tool results) as JSON.
    blocks         TEXT          NOT NULL,
    model          VARCHAR(80),
    input_tokens   BIGINT,
    output_tokens  BIGINT,
    created_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_builder_entries PRIMARY KEY (id),
    CONSTRAINT uq_builder_entries_seq UNIQUE (change_id, seq),
    CONSTRAINT fk_builder_entries_change FOREIGN KEY (change_id) REFERENCES builder_changes (id) ON DELETE CASCADE,
    CONSTRAINT ck_builder_entries_role CHECK (role IN ('PERSON', 'AGENT', 'RESULTS'))
);
