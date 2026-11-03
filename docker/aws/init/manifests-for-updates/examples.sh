aws s3 cp messaging-manifest-local.json s3://kockpit-manifest-bucket --endpoint-url=http://localhost:4566
aws s3 cp ../manifests/messaging-manifest-local.json s3://kockpit-manifest-bucket --endpoint-url=http://localhost:4566
aws s3 cp insurance-manifest-local.json s3://kockpit-manifest-bucket --endpoint-url=http://localhost:4566
aws s3 cp ../manifests/insurance-manifest-local.json s3://kockpit-manifest-bucket --endpoint-url=http://localhost:4566

aws s3 cp ../manifests/kockpit-samples-manifest-local.json s3://kockpit-manifest-bucket --endpoint-url=http://localhost:4566
aws s3 cp ../manifests/kockpit-dynaconfig-manifest-local.json s3://kockpit-manifest-bucket --endpoint-url=http://localhost:4566


