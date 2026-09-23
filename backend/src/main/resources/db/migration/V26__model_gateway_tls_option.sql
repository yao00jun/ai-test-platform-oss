-- Company-internal model gateways often use self-signed or private-CA certificates; trusting them is an explicit opt-in.
ALTER TABLE ai_model_config ADD COLUMN trust_self_signed BOOLEAN NOT NULL DEFAULT FALSE;
