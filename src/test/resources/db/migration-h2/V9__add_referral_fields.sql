ALTER TABLE chat_message
    ADD COLUMN referral_source_type VARCHAR(64) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_source_id VARCHAR(191) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_source_url VARCHAR(1024) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_headline VARCHAR(1024) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_body VARCHAR(4096) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_image_url VARCHAR(1024) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_thumbnail_url VARCHAR(1024) DEFAULT NULL;
ALTER TABLE chat_message
    ADD COLUMN referral_welcome_text VARCHAR(4096) DEFAULT NULL;
