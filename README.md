# FinTube

FinTube is a full-stack finance video application starter built with Angular 21 and Spring Boot 4 (Java 21).

## Hello world

The home page calls the Spring Boot API at `GET /api/hello` and displays the returned message.

## Run locally

In one terminal, start the API:

```bash
cd server
./mvnw spring-boot:run
```

In a second terminal, start the Angular development server:

```bash
cd client
npm install
npm start
```

Open http://localhost:4200. The API is available at http://localhost:8080/api/hello and the health probe at http://localhost:8080/actuator/health.

## Production build and container

```bash
cd server
./mvnw package
docker build -t fintube-server:local .
docker run --rm -p 8080:8080 fintube-server:local
```

The Spring Boot server serves the compiled Angular application in production.

## Release

Set your registry path, authenticate Docker to that registry, then run:

```bash
cd server
IMAGE_REPOSITORY=ghcr.io/YOUR_GITHUB_USERNAME/fintube-server ./deploy/release.sh 0.1.0
```

The release script builds a fresh full-stack artifact, publishes `linux/amd64` and `linux/arm64` images, and creates a local annotated Git tag. Push the tag after reviewing it.
