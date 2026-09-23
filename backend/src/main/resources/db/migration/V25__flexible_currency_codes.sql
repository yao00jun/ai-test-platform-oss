-- Providers bill in ISO currencies, stablecoins, points or credits; keep the code free-form.
ALTER TABLE ai_model_price MODIFY currency VARCHAR(16) NOT NULL;
ALTER TABLE ai_model_invocation MODIFY currency VARCHAR(16);
