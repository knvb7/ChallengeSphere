# ChallengeSphere Backend Runbook

## 1) Local startup

```bash
./mvnw spring-boot:run
```

Backend starts on `http://localhost:8080`.

## 2) Local architecture defaults

- DB: local PostgreSQL by default
- Migrations: Flyway (`src/main/resources/db/migration`)
- Auth: JWT access + refresh tokens
- Security: stateless, bearer auth
- Cache: simple local cache type (Redis beans still present for prod wiring)

## 3) AWS RDS PostgreSQL

The production profile is configured for a normal PostgreSQL JDBC connection, suitable for AWS RDS:

```bash
SPRING_PROFILES_ACTIVE=prod \
DB_HOST='<rds-endpoint>' \
DB_PORT=5432 \
DB_NAME='<database>' \
DB_USERNAME='<username>' \
DB_PASSWORD='<password>' \
./mvnw spring-boot:run
```

AWS credentials are resolved through the AWS default provider chain. For local runs, use environment variables or an AWS profile. In AWS runtime, prefer an IAM role.

Current production region defaults:

- Region: `ap-south-1`
- RDS endpoint: `database-1.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com:5432`
- S3 media bucket: `challengesphere-submissions-prod-ap-south-1`

## 3.1) AWS ElastiCache Redis

Production Redis is a small single-node ElastiCache Redis OSS cluster for the current ~100 DAU scale:

- Region: `ap-south-1`
- Cluster ID: `challengesphere-redis-prod`
- Node type: `cache.t4g.micro`
- Endpoint: `challengesphere-redis-prod.zt14s7.0001.aps1.cache.amazonaws.com:6379`
- Security group: `sg-02d1c11a5ff3dcf35`
- Subnet group: `challengesphere-redis-subnets`

Backend runtime variables:

```bash
SPRING_CACHE_TYPE=redis
SPRING_REDIS_HOST=challengesphere-redis-prod.zt14s7.0001.aps1.cache.amazonaws.com
SPRING_REDIS_PORT=6379
SPRING_REDIS_SSL=false
REDIS_HOST=challengesphere-redis-prod.zt14s7.0001.aps1.cache.amazonaws.com
REDIS_PORT=6379
REDIS_SSL=false
REDIS_DATABASE=0
```

Access is restricted to the backend EC2 security groups attached to the production instances.

## 4) API docs

- OpenAPI JSON: `/v3/api-docs`
- Swagger UI: `/swagger-ui.html`

## 5) Auth flow

1. Register user:

```http
POST /api/v1/auth/register
```

2. Login:

```http
POST /api/v1/auth/login
```

3. Refresh token:

```http
POST /api/v1/auth/refresh
```

4. Get current user:

```http
GET /api/v1/auth/me
Authorization: Bearer <access-token>
```

## 6) Security model

Public endpoints:

- `/api/v1/auth/**`
- `/api/v1/users/register`
- `/v3/api-docs/**`
- `/swagger-ui/**`
- `/swagger-ui.html`
- `/actuator/health`
- `/actuator/info`

All other `/api/v1/**` endpoints require `Authorization: Bearer <token>`.

## 7) Tests

```bash
./mvnw test
```

Current integration coverage includes:

- Context boot
- Auth register/login/me flow
- Security access control smoke checks

## 8) Key config

See `src/main/resources/application.properties`:

- `jwt.secret`
- `jwt.access-token.expiration`
- `jwt.refresh-token.expiration`
- `app.cors.allowed-origins`
- `spring.datasource.*`
- `spring.flyway.*`
- `aws.region`
- `aws.s3.bucket.name`
- `aws.rekognition.*`

## 9) Production email delivery

Production email uses Gmail SMTP as `ironside-dev@challengesphere.com`. The
Google app password is not stored in Git, the application JAR, or the systemd
environment. `MailConfig` reads it at startup from the encrypted Systems
Manager Parameter Store value:

```text
/challengesphere/prod/mail-app-password
```

The production runtime configuration contains only the non-secret parameter
name:

```bash
APP_MAIL_ENABLED=true
MAIL_USERNAME=ironside-dev@challengesphere.com
MAIL_PASSWORD_PARAMETER_NAME=/challengesphere/prod/mail-app-password
```

The EC2 role must have `ssm:GetParameter` for that exact parameter ARN. Rotate
the credential by replacing the SecureString value with a newly generated
Google app password and restarting `challengesphere.service`.
