#!/bin/bash
export AWS_DEFAULT_REGION=eu-west-1
awslocal s3 mb s3://wcp-manifest-bucket
awslocal s3 cp /manifests s3://wcp-manifest-bucket --recursive;

awslocal s3 mb s3://kockpit-data
awslocal s3 mb s3://kockpit-manifests
awslocal s3 cp /manifests s3://kockpit-manifests --recursive;
