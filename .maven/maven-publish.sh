#!/bin/bash

## set variables/constants required by the script
# The current tag of github's branch
# Bail out if CURRENT_TAG is not set
if [ -z "$CURRENT_TAG" ]; then
  echo "Error: CURRENT_TAG is not set. This script requires a tag to be set."
  exit 1
fi

# Check the MAVEN_USERNAME and MAVEN_PASSWORD are set
if [ -z "$MAVEN_USERNAME" ]; then
  echo "Error: MAVEN_USERNAME is not set. This script requires a username to be set."
  exit 1
fi

if [ -z "$MAVEN_PASSWORD" ]; then
  echo "Error: MAVEN_PASSWORD is not set. This script requires a password to be set."
  exit 1
fi

# Publishing type for the Central Portal upload. Change this one line (or export
# PUBLISHING_TYPE before running / set it in the workflow env) to switch modes:
#   AUTOMATIC    - validate AND immediately release to Maven Central. IRREVERSIBLE.
#   USER_MANAGED - validate and hold as a pending deployment; nothing goes public
#                  until you click Publish (or Drop) at central.sonatype.com.
#                  Use this for a safe release rehearsal.
PUBLISHING_TYPE="${PUBLISHING_TYPE:-USER_MANAGED}"

# Fail fast on a typo rather than sending a bad value to Sonatype.
if [ "$PUBLISHING_TYPE" != "AUTOMATIC" ] && [ "$PUBLISHING_TYPE" != "USER_MANAGED" ]; then
  echo "Error: PUBLISHING_TYPE must be 'AUTOMATIC' or 'USER_MANAGED' (got: '${PUBLISHING_TYPE}')."
  exit 1
fi
echo "Publishing type: ${PUBLISHING_TYPE}"

# The body artifact name
BODY_ARTIFACT="service.retrofit-${CURRENT_TAG}.zip"

# The username:password for the maven repository
MAVEN_CREDENTIALS=$(printf "${MAVEN_USERNAME}:${MAVEN_PASSWORD}" | base64)
# Publish the body artifact
curl --request POST \
  --verbose \
  --header "Authorization: Bearer ${MAVEN_CREDENTIALS}" \
  --form "bundle=@${BODY_ARTIFACT}" \
  "https://central.sonatype.com/api/v1/publisher/upload?publishingType=${PUBLISHING_TYPE}&name=service.retrofit"

