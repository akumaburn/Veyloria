-- MySQL initialization script for Veyloria
-- This runs on first container startup (docker-entrypoint-initdb.d)
--
-- The docker-entrypoint already creates MYSQL_USER with `GRANT ALL` on the
-- app database. Startup migrations need CREATE/ALTER/INDEX/REFERENCES as well
-- as DML, so retain those scoped privileges without DROP or GRANT OPTION.
-- This script assumes MYSQL_DATABASE and MYSQL_USER are both chatllm.

-- Ensure the database uses utf8mb4
ALTER DATABASE CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- Restrict privileges to the app schema (startup migrations require limited DDL)
REVOKE ALL PRIVILEGES ON `chatllm`.* FROM 'chatllm'@'%';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, REFERENCES ON `chatllm`.* TO 'chatllm'@'%';
FLUSH PRIVILEGES;
