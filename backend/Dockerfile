FROM golang:1.26-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -o /server ./cmd/server

FROM alpine:3.22
RUN adduser -D app
RUN mkdir -p /data/media && chown -R app:app /data
WORKDIR /data
ENV MEDIA_DIR=/data/media
USER app
COPY --from=build /server /server
EXPOSE 8080
ENTRYPOINT ["/server"]

