#!/bin/bash
export AWS_DEFAULT_REGION=eu-west-1

echo "==================="
echo "configuring Dynamo"
awslocal dynamodb create-table --table-name SqsDocument --attribute-definitions AttributeName=id,AttributeType=S --key-schema AttributeName=id,KeyType=HASH --provisioned-throughput ReadCapacityUnits=1,WriteCapacityUnits=1
awslocal dynamodb create-table \
    --table-name sqsdlq \
    --key-schema AttributeName=partitionKey,KeyType=HASH AttributeName=id,KeyType=RANGE \
    --attribute-definitions AttributeName=partitionKey,AttributeType=S AttributeName=id,AttributeType=S \
    --provisioned-throughput ReadCapacityUnits=40,WriteCapacityUnits=40
awslocal dynamodb create-table \
    --table-name SqsDocumentV2 \
    --key-schema AttributeName=partitionKey,KeyType=HASH AttributeName=id,KeyType=RANGE \
    --attribute-definitions AttributeName=partitionKey,AttributeType=S AttributeName=id,AttributeType=S \
    --provisioned-throughput ReadCapacityUnits=40,WriteCapacityUnits=40


echo "configuring SQS"
awslocal sqs create-queue --queue-name booking-event-notif-dlq.fifo --attributes FifoQueue=true,ContentBasedDeduplication=true
awslocal sqs create-queue --queue-name booking-event-notif-dlq-rec.fifo --attributes FifoQueue=true,ContentBasedDeduplication=true
awslocal sqs create-queue --queue-name booking-event-notif.fifo --attributes FifoQueue=true,ContentBasedDeduplication=true
awslocal sqs create-queue --queue-name insurance-cancellation-notif-dlq
awslocal sqs create-queue --queue-name insurance-cancellation-notif
awslocal sqs create-queue --queue-name insurance-payment-status-update
awslocal sqs create-queue --queue-name insurance-payment-status-update-dlq
awslocal sqs create-queue --queue-name kockpit-samples-audit-sqs-local
awslocal sqs create-queue --queue-name kockpit-samples-sqsdlq-local
awslocal sqs create-queue --queue-name kockpit-samples-sqsdlq-local-dlq.fifo --attributes FifoQueue=true,ContentBasedDeduplication=false
awslocal sqs create-queue --queue-name kockpit-samples-sqsdlq-local.fifo --attributes FifoQueue=true,ContentBasedDeduplication=false
awslocal sqs create-queue --queue-name sqs-sbk
awslocal sqs create-queue --queue-name sqs-sbk-dlq
awslocal sqs create-queue --queue-name kockpit-samples-sqsdlq-dlq


echo "sending messages in SQS"
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/booking-event-notif-dlq.fifo --message-body "body-booking-event-notif-dlq"  --message-group-id "message-group-id"  --message-attributes file://${BASH_SOURCE%/*}/message-attributes.json
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/insurance-cancellation-notif-dlq --message-body "body-insurance-cancellation-notif-dlq"  --message-attributes file://${BASH_SOURCE%/*}/message-attributes.json
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/insurance-cancellation-notif-dlq --message-body "body-insurance-cancellation-notif-dlq"
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/insurance-cancellation-notif-dlq --message-body file://${BASH_SOURCE%/*}/message-body.json --message-attributes file://${BASH_SOURCE%/*}/message-attributes.json
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/kockpit-samples-sqsdlq-local-dlq.fifo --message-body "body-kockpit-samples-sqsdlq-local-dlq" --message-deduplication-id="dedup" --message-group-id "samples-message-group-id" --message-attributes file://${BASH_SOURCE%/*}/message-attributes.json
awslocal sqs send-message --queue-url http://localhost:4566/000000000000/kockpit-samples-sqsdlq-local-dlq.fifo --message-body "body-kockpit-samples-sqsdlq-local-dlq" --message-deduplication-id="dedup" --message-group-id "samples-message-group-id" --message-attributes file://${BASH_SOURCE%/*}/message-attributes.json
echo "==================="


