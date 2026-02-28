#!/bin/bash
set -e

BOOTSTRAP="kafka:9092"

echo "Waiting for Kafka to be ready..."
cub kafka-ready -b $BOOTSTRAP 1 30

echo "Creating Kafka topics..."

kafka-topics --bootstrap-server $BOOTSTRAP --create --if-not-exists \
  --topic order.events \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000

kafka-topics --bootstrap-server $BOOTSTRAP --create --if-not-exists \
  --topic inventory.events \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000

kafka-topics --bootstrap-server $BOOTSTRAP --create --if-not-exists \
  --topic payment.events \
  --partitions 3 \
  --replication-factor 1 \
  --config retention.ms=604800000

echo "Topics created:"
kafka-topics --bootstrap-server $BOOTSTRAP --list
