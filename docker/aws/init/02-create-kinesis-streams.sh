#!/bin/bash

export AWS_DEFAULT_REGION=eu-west-1
awslocal kinesis create-stream --stream-name wcp-sdk-stream-communication-heartbit-local --shard-count 1
awslocal kinesis create-stream --stream-name wcp-sdk-stream-communication-wcp2app-local --shard-count 1
awslocal kinesis create-stream --stream-name wcp-sdk-stream-communication-app2wcp-local --shard-count 1
awslocal kinesis create-stream --stream-name auditstream-dev --shard-count 1
awslocal kinesis create-stream --stream-name auditstream-local --shard-count 1
awslocal kinesis create-stream --stream-name wcp-samples-audit-kinesis-local --shard-count 1