# Deployment Guide - Redis POC

This guide covers deploying the Redis POC application across different environments from local development to production Kubernetes clusters.

## Table of Contents

1. [Local Development](#local-development)
2. [Docker Deployment](#docker-deployment)
3. [Kubernetes Deployment](#kubernetes-deployment)
4. [Cloud Providers](#cloud-providers)
5. [Performance Tuning](#performance-tuning)
6. [Monitoring & Observability](#monitoring--observability)
7. [Security Hardening](#security-hardening)
8. [Disaster Recovery](#disaster-recovery)

## Local Development

### Prerequisites
- Java 17+
- Gradle 7.0+
- Docker (optional, for external Redis)

### Option 1: Embedded Redis (Recommended for Beginners)

**Zero setup required** - uses embedded Redis

```bash
# Run with local profile
./gradlew bootRun --args='--spring.profiles.active=local'

# Application available at http://localhost:8080
# Embedded Redis runs on port 6370
```

**Resource Usage**:
- Memory: ~500-800MB
- CPU: 1-2 cores
- Disk: ~200MB

### Option 2: External Redis

```bash
# Start Redis
docker run -d \
  --name redis \
  -p 6379:6379 \
  redis:7-alpine

# Run application
./gradlew bootRun --args='--spring.profiles.active=dev'
```

### Development with Hot Reload

```bash
# Enable Spring Boot DevTools
./gradlew bootRun --args='--spring.devtools.restart.enabled=true'
```

## Docker Deployment

### Single Container Build

#### Build Application JAR

```bash
./gradlew clean build
```

#### Create Dockerfile

```dockerfile
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Copy JAR
COPY build/libs/redis-poc-0.0.1-SNAPSHOT.jar app.jar

# Expose port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=3s --start-period=40s --retries=3 \
  CMD wget --quiet --tries=1 --spider http://localhost:8080/actuator/health || exit 1

# Run application
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### Build and Run

```bash
# Build image
docker build -t redis-poc:latest .

# Run container
docker run -d \
  --name redis-poc \
  -p 8080:8080 \
  -e SPRING_PROFILES_ACTIVE=dev \
  -e SPRING_REDIS_HOST=host.docker.internal \
  redis-poc:latest
```

### Docker Compose - Full Stack

#### docker-compose.yml

```yaml
version: '3.8'

services:
  # Redis Service
  redis:
    image: redis:7-alpine
    container_name: redis-poc-redis
    ports:
      - "6379:6379"
    volumes:
      - redis-data:/data
    command: redis-server --appendonly yes
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 10s
      timeout: 3s
      retries: 3

  # PostgreSQL Database
  postgres:
    image: postgres:15-alpine
    container_name: redis-poc-db
    ports:
      - "5432:5432"
    environment:
      POSTGRES_DB: redispoc
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    volumes:
      - postgres-data:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U postgres"]
      interval: 10s
      timeout: 5s
      retries: 5

  # Kafka (Optional for prod features)
  kafka:
    image: confluentinc/cp-kafka:7.5.0
    container_name: redis-poc-kafka
    ports:
      - "9092:9092"
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
    depends_on:
      - zookeeper

  zookeeper:
    image: confluentinc/cp-zookeeper:7.5.0
    container_name: redis-poc-zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000

  # Application
  app:
    build: .
    container_name: redis-poc-app
    ports:
      - "8080:8080"
    environment:
      SPRING_PROFILES_ACTIVE: prod
      SPRING_REDIS_HOST: redis
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/redispoc
      SPRING_DATASOURCE_USERNAME: postgres
      SPRING_DATASOURCE_PASSWORD: postgres
      SPRING_KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    depends_on:
      redis:
        condition: service_healthy
      postgres:
        condition: service_healthy
    healthcheck:
      test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:8080/actuator/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 40s

  # Prometheus (Monitoring)
  prometheus:
    image: prom/prometheus:latest
    container_name: redis-poc-prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus.yml:/etc/prometheus/prometheus.yml
      - prometheus-data:/prometheus
    command:
      - '--config.file=/etc/prometheus/prometheus.yml'

  # Grafana (Visualization)
  grafana:
    image: grafana/grafana:latest
    container_name: redis-poc-grafana
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
    volumes:
      - grafana-data:/var/lib/grafana
    depends_on:
      - prometheus

volumes:
  redis-data:
  postgres-data:
  prometheus-data:
  grafana-data:
```

#### prometheus.yml

```yaml
global:
  scrape_interval: 15s

scrape_configs:
  - job_name: 'redis-poc'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets: ['app:8080']
```

#### Start Full Stack

```bash
# Start all services
docker-compose up -d

# Check status
docker-compose ps

# View logs
docker-compose logs -f app

# Stop all services
docker-compose down

# Stop and remove volumes (clean slate)
docker-compose down -v
```

## Kubernetes Deployment

### Prerequisites
- Kubernetes cluster (minikube, EKS, GKE, AKS)
- kubectl configured
- Helm 3+ (optional)

### Namespace Creation

```yaml
# namespace.yaml
apiVersion: v1
kind: Namespace
metadata:
  name: redis-poc
```

```bash
kubectl apply -f namespace.yaml
```

### Redis Deployment

```yaml
# redis-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis
  namespace: redis-poc
spec:
  replicas: 1
  selector:
    matchLabels:
      app: redis
  template:
    metadata:
      labels:
        app: redis
    spec:
      containers:
      - name: redis
        image: redis:7-alpine
        ports:
        - containerPort: 6379
        resources:
          requests:
            memory: "256Mi"
            cpu: "250m"
          limits:
            memory: "512Mi"
            cpu: "500m"
        volumeMounts:
        - name: redis-data
          mountPath: /data
      volumes:
      - name: redis-data
        persistentVolumeClaim:
          claimName: redis-pvc

---
apiVersion: v1
kind: Service
metadata:
  name: redis
  namespace: redis-poc
spec:
  selector:
    app: redis
  ports:
  - port: 6379
    targetPort: 6379
  type: ClusterIP

---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: redis-pvc
  namespace: redis-poc
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 5Gi
```

### Application Deployment

```yaml
# app-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: redis-poc-app
  namespace: redis-poc
spec:
  replicas: 3
  selector:
    matchLabels:
      app: redis-poc
  template:
    metadata:
      labels:
        app: redis-poc
    spec:
      containers:
      - name: app
        image: redis-poc:latest
        imagePullPolicy: Always
        ports:
        - containerPort: 8080
        env:
        - name: SPRING_PROFILES_ACTIVE
          value: "prod"
        - name: SPRING_REDIS_HOST
          value: "redis"
        - name: SPRING_REDIS_PORT
          value: "6379"
        - name: SPRING_DATASOURCE_URL
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: url
        - name: SPRING_DATASOURCE_USERNAME
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: username
        - name: SPRING_DATASOURCE_PASSWORD
          valueFrom:
            secretKeyRef:
              name: db-credentials
              key: password
        resources:
          requests:
            memory: "1Gi"
            cpu: "500m"
          limits:
            memory: "2Gi"
            cpu: "1000m"
        livenessProbe:
          httpGet:
            path: /actuator/health/liveness
            port: 8080
          initialDelaySeconds: 60
          periodSeconds: 10
          timeoutSeconds: 5
          failureThreshold: 3
        readinessProbe:
          httpGet:
            path: /actuator/health/readiness
            port: 8080
          initialDelaySeconds: 30
          periodSeconds: 5
          timeoutSeconds: 3
          failureThreshold: 3

---
apiVersion: v1
kind: Service
metadata:
  name: redis-poc-service
  namespace: redis-poc
spec:
  selector:
    app: redis-poc
  ports:
  - port: 80
    targetPort: 8080
  type: LoadBalancer

---
apiVersion: v1
kind: Secret
metadata:
  name: db-credentials
  namespace: redis-poc
type: Opaque
stringData:
  url: jdbc:postgresql://postgres-service:5432/redispoc
  username: postgres
  password: your-secure-password
```

### ConfigMap for Application Properties

```yaml
# configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
  namespace: redis-poc
data:
  application.yml: |
    spring:
      application:
        name: redis-poc
      cache:
        type: redis
        redis:
          time-to-live: 600000
      redis:
        lettuce:
          pool:
            max-active: 20
            max-idle: 10
            min-idle: 5
```

### Horizontal Pod Autoscaler

```yaml
# hpa.yaml
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: redis-poc-hpa
  namespace: redis-poc
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: redis-poc-app
  minReplicas: 2
  maxReplicas: 10
  metrics:
  - type: Resource
    resource:
      name: cpu
      target:
        type: Utilization
        averageUtilization: 70
  - type: Resource
    resource:
      name: memory
      target:
        type: Utilization
        averageUtilization: 80
```

### Deploy to Kubernetes

```bash
# Apply all configurations
kubectl apply -f namespace.yaml
kubectl apply -f redis-deployment.yaml
kubectl apply -f app-deployment.yaml
kubectl apply -f configmap.yaml
kubectl apply -f hpa.yaml

# Check deployment status
kubectl get pods -n redis-poc
kubectl get services -n redis-poc

# View logs
kubectl logs -f deployment/redis-poc-app -n redis-poc

# Scale manually
kubectl scale deployment redis-poc-app --replicas=5 -n redis-poc

# Port forward for local access
kubectl port-forward service/redis-poc-service 8080:80 -n redis-poc
```

## Cloud Providers

### AWS (EKS + ElastiCache)

#### Prerequisites
- AWS CLI configured
- eksctl installed
- kubectl configured

#### Create EKS Cluster

```bash
eksctl create cluster \
  --name redis-poc-cluster \
  --region us-east-1 \
  --nodegroup-name standard-workers \
  --node-type t3.medium \
  --nodes 3 \
  --nodes-min 2 \
  --nodes-max 5 \
  --managed
```

#### Create ElastiCache Redis

```bash
aws elasticache create-replication-group \
  --replication-group-id redis-poc \
  --replication-group-description "Redis for POC app" \
  --cache-node-type cache.t3.medium \
  --engine redis \
  --engine-version 7.0 \
  --num-cache-clusters 3 \
  --automatic-failover-enabled \
  --multi-az-enabled \
  --cache-subnet-group-name my-subnet-group \
  --security-group-ids sg-xxxxx
```

#### Update Application Configuration

```yaml
env:
- name: SPRING_REDIS_HOST
  value: "redis-poc.xxxxx.cache.amazonaws.com"
- name: SPRING_REDIS_PORT
  value: "6379"
- name: SPRING_REDIS_SSL
  value: "true"
```

### Azure (AKS + Azure Cache for Redis)

```bash
# Create resource group
az group create --name redis-poc-rg --location eastus

# Create AKS cluster
az aks create \
  --resource-group redis-poc-rg \
  --name redis-poc-aks \
  --node-count 3 \
  --enable-addons monitoring \
  --generate-ssh-keys

# Create Azure Cache for Redis
az redis create \
  --resource-group redis-poc-rg \
  --name redis-poc-cache \
  --location eastus \
  --sku Premium \
  --vm-size P1

# Get credentials
az aks get-credentials --resource-group redis-poc-rg --name redis-poc-aks
```

### GCP (GKE + Memorystore)

```bash
# Create GKE cluster
gcloud container clusters create redis-poc-cluster \
  --zone us-central1-a \
  --num-nodes 3 \
  --machine-type n1-standard-2

# Create Memorystore (Redis)
gcloud redis instances create redis-poc \
  --size=5 \
  --region=us-central1 \
  --redis-version=redis_7_0

# Get credentials
gcloud container clusters get-credentials redis-poc-cluster --zone us-central1-a
```

## Performance Tuning

### JVM Tuning

```dockerfile
# Dockerfile with optimized JVM settings
ENTRYPOINT ["java", \
  "-XX:+UseG1GC", \
  "-XX:MaxGCPauseMillis=200", \
  "-XX:+UseStringDeduplication", \
  "-Xms1g", \
  "-Xmx2g", \
  "-XX:+HeapDumpOnOutOfMemoryError", \
  "-XX:HeapDumpPath=/dumps", \
  "-jar", "app.jar"]
```

### Redis Connection Pool

```yaml
spring:
  redis:
    lettuce:
      pool:
        max-active: 20      # Max connections
        max-idle: 10        # Max idle connections
        min-idle: 5         # Min idle connections
        max-wait: 2000      # Max wait time (ms)
      timeout: 5000         # Connection timeout (ms)
```

### Caching Strategy

```yaml
spring:
  cache:
    redis:
      time-to-live: 600000  # 10 minutes
      cache-null-values: false
      use-key-prefix: true
```

## Monitoring & Observability

### Prometheus Scraping

Add annotations to deployment:

```yaml
metadata:
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/path: "/actuator/prometheus"
    prometheus.io/port: "8080"
```

### Grafana Dashboards

Import Spring Boot dashboard:
- Dashboard ID: 11378 (Spring Boot Statistics)
- Dashboard ID: 4701 (JVM Micrometer)

### Custom Alerts

```yaml
# prometheus-alerts.yaml
groups:
- name: redis-poc-alerts
  rules:
  - alert: HighMemoryUsage
    expr: jvm_memory_used_bytes{area="heap"} / jvm_memory_max_bytes{area="heap"} > 0.9
    for: 5m
    labels:
      severity: warning
    annotations:
      summary: "High memory usage detected"
```

## Security Hardening

### Enable Redis SSL/TLS

```yaml
spring:
  redis:
    ssl: true
    password: ${REDIS_PASSWORD}
```

### Network Policies

```yaml
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: redis-poc-network-policy
  namespace: redis-poc
spec:
  podSelector:
    matchLabels:
      app: redis-poc
  policyTypes:
  - Ingress
  - Egress
  ingress:
  - from:
    - podSelector:
        matchLabels:
          app: nginx-ingress
    ports:
    - protocol: TCP
      port: 8080
  egress:
  - to:
    - podSelector:
        matchLabels:
          app: redis
    ports:
    - protocol: TCP
      port: 6379
```

## Disaster Recovery

### Backup Strategy

```bash
# Redis snapshot backup
kubectl exec -it redis-0 -n redis-poc -- redis-cli BGSAVE

# Copy RDB file
kubectl cp redis-poc/redis-0:/data/dump.rdb ./backups/dump-$(date +%Y%m%d).rdb
```

### Database Backups

```bash
# PostgreSQL backup
kubectl exec -it postgres-0 -n redis-poc -- pg_dump -U postgres redispoc > backup.sql
```

### Restore Procedure

```bash
# Restore Redis
kubectl cp ./backups/dump-20240101.rdb redis-poc/redis-0:/data/dump.rdb
kubectl exec -it redis-0 -n redis-poc -- redis-cli SHUTDOWN
# Pod will restart and load RDB

# Restore PostgreSQL
kubectl exec -i postgres-0 -n redis-poc -- psql -U postgres redispoc < backup.sql
```

## Troubleshooting

### Common Issues

1. **Redis Connection Refused**
   ```bash
   # Check Redis is running
   kubectl get pods -n redis-poc | grep redis
   
   # Check logs
   kubectl logs deployment/redis -n redis-poc
   ```

2. **High Memory Usage**
   ```bash
   # Check memory metrics
   kubectl top pods -n redis-poc
   
   # Adjust JVM heap
   -Xms2g -Xmx4g
   ```

3. **Slow Performance**
   ```bash
   # Check connection pool
   # Increase max-active connections
   spring.redis.lettuce.pool.max-active=50
   ```

## Summary

This deployment guide covers:
- ✅ Local development (embedded Redis)
- ✅ Docker containerization
- ✅ Kubernetes orchestration
- ✅ Cloud provider deployment (AWS, Azure, GCP)
- ✅ Performance tuning
- ✅ Monitoring setup
- ✅ Security hardening
- ✅ Disaster recovery

For additional support, refer to:
- [README.md](README.md) - Project overview
- [TESTING_GUIDE.md](TESTING_GUIDE.md) - Testing procedures
- [JUNIT_TEST_GUIDE.md](JUNIT_TEST_GUIDE.md) - JUnit test suite
