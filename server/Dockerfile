FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 GOOS=linux go build -o /out/kipotify ./cmd/server

FROM alpine:3.22
WORKDIR /app
RUN adduser -D -H kipotify
COPY --from=build /out/kipotify /app/kipotify
COPY migrations /app/migrations
COPY seed /app/seed
USER kipotify
EXPOSE 8080
ENTRYPOINT ["/app/kipotify"]
