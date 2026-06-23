# Flyway V2 deployment

## Supported paths

- Empty database: run Flyway normally; CI/test applies V1 then V2.
- Existing database already managed by Flyway at V1: take a backup and run V2 normally.
- Legacy database with V1 tables but no `flyway_schema_history`: first run `legacy-v1-preflight.sql`. Only when every V2 column/table reports absent may the database be baselined at version 1, then migrated to V2.

Never baseline a database to V1 when any V2 object already exists. That creates a partially upgraded schema and V2 will fail on duplicate columns. A database with mixed V1/V2 objects must be repaired explicitly from a backup or with a reviewed one-off script.

Before deployment:

1. Back up the schema and business data.
2. Run the preflight query and retain its output in the change record.
3. Confirm there are no duplicate active conversations per resource.
4. Run `flyway validate`, then `flyway migrate`.
5. If `flyway_schema_history.success = 0`, stop deployment. Do not use `repair` until the failed statement and schema state have been reviewed.

Required runtime secrets are supplied through `DB_PASSWORD`, `CRM_SIGN`, `CRM_CLUE_SIGN`, `WHATSAPP_ACCESS_TOKEN`, `WHATSAPP_APP_SECRET`, `WHATSAPP_VERIFY_TOKEN`, `DIFY_API_KEY`, and `SECURITY_JWT_SECRET`. Previously committed values must be rotated at their providers.

For integration and E2E environments set `INTEGRATION_SCHEDULERS_ENABLED=false`. This prevents registration of the inactive-agent scanner, conversation-timeout scanner, and realtime outbox publisher. Restart the JVM after changing this value; changing source or environment variables does not affect an already running process.
