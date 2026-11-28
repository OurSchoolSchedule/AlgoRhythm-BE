#!/usr/bin/env bash
set -Eeuo pipefail
export AWS_PAGER=""
api_host=edu.rssolplan.com
release="$GITHUB_RUN_ID-$GITHUB_RUN_ATTEMPT"
for key in AWS_REGION AWS_ACCOUNT_ID AWS_DEPLOY_ROLE_ARN ECR_REPOSITORY EC2_INSTANCE_ID RUNTIME_SECRET_ARN TARGET_GROUP_ARN; do
  [[ -n ${!key:-} ]] || { echo "Missing GitHub environment variable: $key"; exit 1; }
done
[[ "$AWS_ACCOUNT_ID" =~ ^[0-9]{12}$ && "$AWS_REGION" =~ ^[a-z]{2}-[a-z]+-[0-9]+$ ]]
[[ "$EC2_INSTANCE_ID" =~ ^i-([0-9a-f]{8}|[0-9a-f]{17})$ ]]
[[ "$ECR_REPOSITORY" =~ ^[a-z0-9][a-z0-9._/-]*$ ]]
[[ "$AWS_DEPLOY_ROLE_ARN" == arn:aws:iam::"$AWS_ACCOUNT_ID":role/* ]]
[[ "$TARGET_GROUP_ARN" == arn:aws:elasticloadbalancing:"$AWS_REGION":"$AWS_ACCOUNT_ID":targetgroup/* ]]
[[ "$RUNTIME_SECRET_ARN" == arn:aws:secretsmanager:"$AWS_REGION":"$AWS_ACCOUNT_ID":secret:* ]]
[[ $(aws sts get-caller-identity --query Account --output text) == "$AWS_ACCOUNT_ID" ]]
repo=$(aws ecr describe-repositories --repository-names "$ECR_REPOSITORY" --query 'repositories[0]' --output json)
registry="$AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com"
uri="$registry/$ECR_REPOSITORY"
jq -e --arg uri "$uri" '.repositoryUri == $uri and .imageTagMutability == "IMMUTABLE"' <<< "$repo" >/dev/null
target=$(aws elbv2 describe-target-groups --target-group-arns "$TARGET_GROUP_ARN" --query 'TargetGroups[0]' --output json)
jq -e '.TargetType == "instance" and .Protocol == "HTTP" and .Port == 8080 and .HealthCheckPath == "/actuator/health" and .Matcher.HttpCode == "200" and (.LoadBalancerArns|length) == 1' <<< "$target" >/dev/null
members=$(aws elbv2 describe-target-health --target-group-arn "$TARGET_GROUP_ARN" --output json)
jq -e --arg id "$EC2_INSTANCE_ID" '(.TargetHealthDescriptions|length) == 1 and .TargetHealthDescriptions[0].Target.Id == $id and .TargetHealthDescriptions[0].Target.Port == 8080' <<< "$members" >/dev/null
lb=$(jq -r '.LoadBalancerArns[0]' <<< "$target")
alb=$(aws elbv2 describe-load-balancers --load-balancer-arns "$lb" --query 'LoadBalancers[0].DNSName' --output text)
[[ "$alb" == *.elb.amazonaws.com ]]
# A run-specific tag permits retries without overwriting an immutable ECR tag.
tag="$GITHUB_SHA-$release"
aws ecr get-login-password | docker login --username AWS --password-stdin "$registry" >/dev/null
docker load -i "$RUNNER_TEMP/aws-image/image.tar" >/dev/null
docker tag "algorhythm-aws:$GITHUB_SHA" "$uri:$tag"
docker push "$uri:$tag"
digest=$(aws ecr describe-images --repository-name "$ECR_REPOSITORY" --image-ids "imageTag=$tag" --query 'imageDetails[0].imageDigest' --output text)
[[ "$digest" =~ ^sha256:[0-9a-f]{64}$ ]]
image="$uri@$digest"

remote() {
  local args id response status attempt
  # @sh quotes every argument for the SSM agent's /bin/sh. Secret values never enter the command.
  args=$(jq -nc --rawfile script deploy/aws/ec2.sh --rawfile validator deploy/aws/runtime_env.py \
    --arg mode "$1" --arg release "$release" --arg image "$image" --arg region "$AWS_REGION" --arg secret "$RUNTIME_SECRET_ARN" \
    '{commands:[(["bash","-c",$script,"algorhythm",$mode,$release,$image,$region,$secret,$validator]|@sh)],executionTimeout:["900"]}')
  id=$(aws ssm send-command --instance-ids "$EC2_INSTANCE_ID" --document-name AWS-RunShellScript \
    --parameters "$args" --timeout-seconds 60 --comment "Algorhythm $1 $release" --query 'Command.CommandId' --output text)
  echo "SSM $1: $id"
  for ((attempt=0; attempt<200; attempt++)); do
    if response=$(aws ssm get-command-invocation --command-id "$id" --instance-id "$EC2_INSTANCE_ID" --output json 2>"$RUNNER_TEMP/ssm-status-error"); then
      status=$(jq -r '.Status' <<< "$response")
      case "$status" in
        Success) return 0 ;;
        Pending|InProgress|Delayed|Cancelling) ;;
        *) echo "SSM command ended: $status. View its diagnostic in Systems Manager."; return 1 ;;
      esac
    elif ! grep -q InvocationDoesNotExist "$RUNNER_TEMP/ssm-status-error"; then
      cat "$RUNNER_TEMP/ssm-status-error" >&2
      return 1
    fi
    sleep 5
  done
  echo "SSM confirmation timed out: $id"
  return 1
}
remote deploy
# Route directly through the intended ALB, even while public DNS still points to Railway.
if ! aws elbv2 wait target-in-service --target-group-arn "$TARGET_GROUP_ARN" --targets "Id=$EC2_INSTANCE_ID,Port=8080" ||
   ! curl --fail --silent --show-error --max-time 20 --dump-header "$RUNNER_TEMP/aws-health.headers" --connect-to "$api_host:443:$alb:443" "https://$api_host/actuator/health" | jq -e '. == {"status":"UP"}' >/dev/null ||
   ! tr -d '\r' < "$RUNNER_TEMP/aws-health.headers" | grep -Fxi "X-Algorhythm-Release: $release" >/dev/null; then
  echo 'ALB or TLS check failed; rolling back'
  remote rollback
  exit 1
fi
echo "Verified $image via https://$api_host/actuator/health on the release ALB" >> "$GITHUB_STEP_SUMMARY"
