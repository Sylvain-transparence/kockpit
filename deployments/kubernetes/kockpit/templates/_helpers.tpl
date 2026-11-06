{{- define "kockpit.name" -}}
{{- .Chart.Name | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "kockpit.fullname" -}}
{{- if contains .Chart.Name .Release.Name -}}
{{- .Release.Name | trunc 50 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name .Chart.Name | trunc 50 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{/* name of a component's resources: <fullname>-<component> */}}
{{- define "kockpit.component" -}}
{{- printf "%s-%s" (include "kockpit.fullname" .root) .name -}}
{{- end -}}

{{- define "kockpit.labels" -}}
app.kubernetes.io/name: {{ include "kockpit.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .name }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version }}
{{- end -}}

{{- define "kockpit.selectorLabels" -}}
app.kubernetes.io/name: {{ include "kockpit.name" .root }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
app.kubernetes.io/component: {{ .name }}
{{- end -}}

{{/* full image reference of a Kockpit application image */}}
{{- define "kockpit.image" -}}
{{- printf "%s/%s:%s" .root.Values.image.registry .image .root.Values.image.tag -}}
{{- end -}}

{{- define "kockpit.kafkaBootstrap" -}}
{{- if .Values.kafka.enabled -}}
{{- printf "%s-kafka:9092" (include "kockpit.fullname" .) -}}
{{- else -}}
{{- required "kafka.externalBootstrapServers is required when kafka.enabled is false" .Values.kafka.externalBootstrapServers -}}
{{- end -}}
{{- end -}}

{{- define "kockpit.opensearchUrl" -}}
{{- if .Values.opensearch.enabled -}}
{{- printf "http://%s-opensearch:9200" (include "kockpit.fullname" .) -}}
{{- else -}}
{{- required "opensearch.externalUrl is required when opensearch.enabled is false" .Values.opensearch.externalUrl -}}
{{- end -}}
{{- end -}}

{{/* volume holding the filesystem storage shared by the backend and the sample app */}}
{{- define "kockpit.dataVolume" -}}
- name: data
{{- if .Values.data.persistence.enabled }}
  persistentVolumeClaim:
    claimName: {{ include "kockpit.fullname" . }}-data
{{- else }}
  emptyDir: {}
{{- end }}
{{- end -}}

{{- define "kockpit.imagePullSecrets" -}}
{{- with .Values.imagePullSecrets }}
imagePullSecrets:
{{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}

{{/* secret holding db-password, jwt-secret and admin-password */}}
{{- define "kockpit.secretName" -}}
{{- .Values.auth.existingSecret | default (printf "%s-secrets" (include "kockpit.fullname" .)) -}}
{{- end -}}

{{- define "kockpit.dbUrl" -}}
{{- printf "jdbc:postgresql://%s-postgres:5432/%s" (include "kockpit.fullname" .) .Values.postgres.database -}}
{{- end -}}

{{/* PostgreSQL connection of the backend (local accounts) and the gateway */}}
{{- define "kockpit.dbEnv" -}}
- { name: KOCKPIT_DB_URL, value: {{ include "kockpit.dbUrl" . | quote }} }
- { name: KOCKPIT_DB_USERNAME, value: {{ .Values.postgres.username | quote }} }
- name: KOCKPIT_DB_PASSWORD
  valueFrom:
    secretKeyRef: { name: {{ include "kockpit.secretName" . }}, key: db-password }
{{- end -}}

{{/* https when the ingress has TLS */}}
{{- define "kockpit.scheme" -}}
{{- if or .Values.ingress.certManagerClusterIssuer .Values.ingress.tls -}}https{{- else -}}http{{- end -}}
{{- end -}}

{{- define "kockpit.consoleOrigin" -}}
{{- printf "%s://%s" (include "kockpit.scheme" .) .Values.ingress.host -}}
{{- end -}}

{{- define "kockpit.ingestUrl" -}}
{{- if and .Values.ingress.enabled .Values.ingress.gatewayHost -}}
{{- printf "%s://%s" (include "kockpit.scheme" .) .Values.ingress.gatewayHost -}}
{{- end -}}
{{- end -}}

{{/* where every pod runs: nodeSelector and tolerations, shared by all components (empty: anywhere) */}}
{{- define "kockpit.scheduling" -}}
{{- with .Values.nodeSelector }}
nodeSelector:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- with .Values.tolerations }}
tolerations:
  {{- toYaml . | nindent 2 }}
{{- end }}
{{- end -}}
