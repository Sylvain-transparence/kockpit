
# config
ES_HOST=http://localhost:9200

# reindex
index_source=kockpit-audit_data-pro-ttl30d-2025.10.17-000010
index_destination=${index_source}.v2

# create index destination
curl -vX PUT ${ES_HOST}/"$index_destination"

# add mapping
curl -vX POST ${ES_HOST}/"$index_destination"/_mapping -H 'Content-Type: application/json' -d @index-template.json

# reindex
curl -vX POST ${ES_HOST}/_reindex -H 'Content-Type: application/json' -d '{
  "source":{
      "index": "'"${index_source}"'"
   },
   "dest":{
      "index": "'"${index_destination}"'"
   }
}
'

# add to alias r/w
curl -vX POST ${ES_HOST}/_aliases -H 'Content-Type: application/json' -d '
  {
    "actions": [
      {
          "add": {
            "alias": "kockpit-audit_data-local-read",
            "index": "'"${index_destination}"'"
          }
      },
      {
          "add": {
            "alias": "kockpit-audit_data-local-ttl30d-write",
            "index": "'"${index_destination}"'",
            "is_write_index": false
          }
      },
      {
          "remove": {
            "alias": "kockpit-audit_data-local-read",
            "index": "'"${index_source}"'"
          }
      },
      {
          "remove": {
            "alias": "kockpit-audit_data-local-ttl30d-write",
            "index": "'"${index_source}"'"
          }
      }
    ]
  }
'
