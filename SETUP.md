# URL Shortener - Setup Guide

## Prerequisites

- Java 17+
- Maven 3.8+
- Docker & Docker Compose
- Git
- 4GB+ RAM for local development

## Quick Start (5 minutes)

### 1. Clone & Navigate
```bash
git clone https://github.com/maheshj1605/url-shortener.git
cd url-shortener
```

### 2. Start Infrastructure (Docker)
```bash
docker-compose -f docker-compose.yml up -d
```

This starts:
- PostgreSQL (port 5432)
- Redis (port 6379)
- Spring Boot app (port 8080)
- Nginx load balancer (port 80)

### 3. Verify Service is Running
```bash
curl http://localhost:8080/api/v1/health
# Response: { "status": "UP", "message": "URL Shortener service is running" }
```

### 4. Create Your First Short URL
```bash
curl -X POST http://localhost:8080/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{
    "originalUrl": "https://github.com/maheshj1605/url-shortener",
    "title": "My Project",
    "expiryDays": 365
  }'
```

Response:
```json
{
  "shortCode": "abc123",
  "shortUrl": "http://short.url/abc123",
  "originalUrl": "https://github.com/maheshj1605/url-shortener",
  "createdAt": "2026-08-28T10:30:00Z",
  "expiresAt": "2027-08-28T10:30:00Z",
  "totalClicks": 0,
  "isActive": true
}
```

### 5. Test Redirect
```bash
# Follow redirect
curl -L http://localhost:8080/abc123
# Returns original URL

# See response headers
curl -I http://localhost:8080/abc123
# HTTP/1.1 301 Moved Permanently
# Location: https://github.com/maheshj1605/url-shortener
```

### 6. View Analytics
```bash
curl http://localhost:8080/api/v1/analytics/abc123
```

---

## Development Setup

### Local Build & Run

```bash
# Build JAR
mvn clean package

# Run directly (requires PostgreSQL & Redis running)
java -jar target/url-shortener-1.0.0.jar

# Or with Maven
mvn spring-boot:run
```

### Configuration

**Application Properties** (`src/main/resources/application.yml`):

```yaml
# Database
spring.datasource.url: jdbc:postgresql://localhost:5432/urlshortener
spring.datasource.username: postgres
spring.datasource.password: postgres

# Redis
spring.data.redis.host: localhost
spring.data.redis.port: 6379

# App
url-shortener.base-url: http://short.url
url-shortener.default-expiry-days: 365

# Snowflake
snowflake.datacenter-id: 1
snowflake.machine-id: 1

# Rate Limiting
rate-limiter.capacity: 100
rate-limiter.refill-rate: 100
```

### IDE Setup

**IntelliJ IDEA**
1. Open project → pom.xml
2. Right-click → "Configure" → "Convert to Maven Project"
3. Maven → Reload projects
4. Run → Edit Configurations → Add "Application"
5. Main class: `com.mahii.urlshortener.UrlShortenerApplication`
6. VM options: `-Xmx512m -Xms256m`

**VS Code**
```bash
# Install extensions
- Extension Pack for Java
- Spring Boot Extension Pack
- REST Client

# F5 to run
```

---

## Testing

### Unit Tests
```bash
mvn test
```

### Integration Tests
```bash
mvn test -Dgroups=integration
```

### Test Coverage Report
```bash
mvn jacoco:report
# Open: target/site/jacoco/index.html
```

### Load Testing (JMeter)

```bash
# Install JMeter
brew install jmeter  # macOS
apt-get install jmeter  # Linux

# Run test plan
jmeter -n -t load-testing/url-shortener.jmx -l results.jtl

# View results
jmeter -g results.jtl -o report/
```

### Manual API Testing

**Create URL**
```bash
curl -X POST http://localhost:8080/api/v1/shorten \
  -H "Content-Type: application/json" \
  -d '{
    "originalUrl": "https://example.com/test",
    "customAlias": "my-link",
    "expiryDays": 30
  }'
```

**Redirect (3 ways)**
```bash
# Using short code
curl http://localhost:8080/abc123

# Using custom alias
curl http://localhost:8080/my-link

# Get redirect location without following
curl -I http://localhost:8080/abc123
```

**Get URL Details**
```bash
curl http://localhost:8080/api/v1/urls/abc123
```

**Get Analytics**
```bash
curl http://localhost:8080/api/v1/analytics/abc123
```

**Delete URL**
```bash
curl -X DELETE http://localhost:8080/api/v1/urls/abc123
```

---

## Docker Operations

### View Logs
```bash
# All services
docker-compose logs -f

# Single service
docker-compose logs -f app
docker-compose logs -f postgres
docker-compose logs -f redis
```

### Scale Service
```bash
# Create multiple instances
docker-compose up -d --scale app=3

# Each instance behind Nginx load balancer
```

### Stop & Cleanup
```bash
# Stop all
docker-compose down

# Remove volumes (database data)
docker-compose down -v

# Rebuild images
docker-compose build --no-cache
docker-compose up -d
```

---

## Kubernetes Deployment (Production)

### Prerequisites
- kubectl installed
- Kubernetes cluster (minikube, GKE, EKS, etc.)

### Deploy
```bash
# Apply manifests
kubectl apply -f deployment/kubernetes/

# Check pods
kubectl get pods

# View logs
kubectl logs -f deployment/url-shortener-app

# Port forward for testing
kubectl port-forward svc/url-shortener-app 8080:8080
```

### Configuration via ConfigMap
```bash
# View current config
kubectl get configmap url-shortener-config -o yaml

# Update config
kubectl edit configmap url-shortener-config
```

### Scaling
```bash
# Scale replicas
kubectl scale deployment url-shortener-app --replicas=5

# Auto-scaling based on metrics
kubectl apply -f deployment/kubernetes/hpa.yml
```

---

## Monitoring

### Health Endpoints

**Application Health**
```bash
curl http://localhost:8080/api/v1/health
```

**Detailed Health**
```bash
curl http://localhost:8080/actuator/health/details
```

### Metrics

**Prometheus Metrics**
```bash
curl http://localhost:8080/actuator/prometheus
```

### Logs

**View logs**
```bash
tail -f logs/url-shortener.log

# With filtering
grep "ERROR" logs/url-shortener.log
```

---

## Troubleshooting

### Port Already in Use
```bash
# Kill process using port
lsof -i :8080
kill -9 <PID>

# Or change port in docker-compose
# ports: - "8081:8080"
```

### Database Connection Error
```bash
# Check PostgreSQL is running
docker ps | grep postgres

# Restart database
docker-compose restart postgres

# Verify connection
psql -U postgres -d urlshortener -c "SELECT 1;"
```

### Redis Connection Error
```bash
# Check Redis is running
docker ps | grep redis

# Test connection
redis-cli ping
# Response: PONG
```

### Out of Memory
```bash
# Increase JVM heap size
JAVA_OPTS="-Xmx1g -Xms512m" mvn spring-boot:run

# Or in docker-compose
environment:
  - JAVA_OPTS=-Xmx1g -Xms512m
```

### Maven Build Fails
```bash
# Clear cache
mvn clean

# Update dependencies
mvn dependency:resolve
mvn dependency:tree

# Rebuild
mvn clean package
```

---

## Performance Tuning

### Database Connection Pool
```yaml
spring.datasource.hikari:
  maximum-pool-size: 30
  minimum-idle: 10
  connection-timeout: 20000
```

### Cache Settings
```yaml
cache:
  l1:
    size: 50000  # Increase for larger deployments
    ttl-minutes: 10
  l2:
    ttl-minutes: 120
```

### Rate Limiter
```yaml
rate-limiter:
  capacity: 1000  # Increase for high-volume APIs
  refill-rate: 1000
```

### JVM Tuning
```bash
JAVA_OPTS="-Xmx2g -Xms1g \
  -XX:+UseG1GC \
  -XX:MaxGCPauseMillis=200 \
  -XX:+ParallelRefProcEnabled"
```

---

## Deployment Checklist

Before deploying to production:

- [ ] All tests passing (>85% coverage)
- [ ] Load testing completed (5000+ RPS)
- [ ] Security audit done (OWASP Top 10)
- [ ] Database backed up
- [ ] Monitoring & alerts configured
- [ ] Runbooks created for incidents
- [ ] SSL/TLS certificates installed
- [ ] API rate limiting enabled
- [ ] Logging aggregation set up (ELK)
- [ ] Disaster recovery plan documented

---

## Useful Commands

```bash
# Build production image
mvn clean package && docker build -t maheshj1605/url-shortener:1.0.0 .

# Push to registry
docker push maheshj1605/url-shortener:1.0.0

# Deploy to production
docker pull maheshj1605/url-shortener:1.0.0
docker run -d --name url-shortener \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://prod-db:5432/url \
  -e SPRING_DATA_REDIS_HOST=prod-redis \
  -p 8080:8080 \
  maheshj1605/url-shortener:1.0.0

# View real-time metrics
watch 'curl -s http://localhost:8080/actuator/metrics | jq .'

# Database query
docker exec url-shortener-db psql -U postgres -d urlshortener \
  -c "SELECT COUNT(*) FROM shortened_urls;"
```

---

## Support & Resources

- 📖 **Documentation**: See `docs/` folder
- 🏗️ **Architecture**: Read `ARCHITECTURE.md`
- 🧪 **Tests**: Check `src/test/` for examples
- 🐛 **Issues**: Report on GitHub
- 💬 **Discussions**: GitHub Discussions

---

**Happy coding! 🚀**
