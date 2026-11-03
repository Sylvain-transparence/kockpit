#!/bin/bash

export AWS_DEFAULT_REGION=eu-west-1
awslocal kinesis create-stream --stream-name kockpit-sdk-stream-communication-heartbit-local --shard-count 1
awslocal kinesis create-stream --stream-name kockpit-sdk-stream-communication-console2app-local --shard-count 1
awslocal kinesis create-stream --stream-name kockpit-sdk-stream-communication-app2console-local --shard-count 1
awslocal kinesis create-stream --stream-name auditstream-dev --shard-count 1
awslocal kinesis create-stream --stream-name auditstream-local --shard-count 1
awslocal kinesis create-stream --stream-name kockpit-samples-audit-kinesis-local --shard-count 1