#!/bin/sh
# 只管理本项目的开发桶配置；不删除对象，不依赖 Spring 或前端进程。
set -eu

bucket=${MEDIA_STORAGE_BUCKET:?必须配置开发桶}
prefix=${MEDIA_KEY_PREFIX:?必须配置开发对象前缀}

# 值将写入 JSON，先限定字符；开发前缀不能误指向生产环境。
if ! printf '%s\n' "$bucket" | grep -Eq '^[a-z0-9][a-z0-9-]{1,61}[a-z0-9]$'; then
    echo '开发桶名须为 3 至 63 位小写字母、数字或连字符，且首尾为字母或数字。' >&2
    exit 1
fi
if ! printf '%s\n' "$prefix" | grep -Eq '^[A-Za-z0-9][A-Za-z0-9/_-]{0,127}$'; then
    echo '对象前缀包含不支持的字符或长度无效。' >&2
    exit 1
fi
case "$prefix" in
    *//*) echo '对象前缀不能包含连续斜杠。' >&2; exit 1 ;;
esac
case "$prefix" in
    */dev) ;;
    *) echo '开发对象前缀必须以 /dev 结尾。' >&2; exit 1 ;;
esac

# 临时配置不落到宿主机；任意 S3 操作失败均使初始化失败。
work_dir=$(mktemp -d)
trap 'rm -rf "$work_dir"' EXIT

s3() {
    aws --endpoint-url http://minio:9000 --cli-connect-timeout 5 --cli-read-timeout 15 s3api "$@"
}

# 已存在的桶继续使用，创建失败（包括认证失败）不能被忽略。
if ! s3 head-bucket --bucket "$bucket" >/dev/null 2>&1; then
    s3 create-bucket --bucket "$bucket" >/dev/null
fi

# 匿名仅允许读取公开前缀内的对象，不开放列桶、上传或临时对象。
cat > "$work_dir/policy.json" <<EOF
{
  "Version": "2012-10-17",
  "Statement": [{
    "Sid": "PublicMediaReadOnly",
    "Effect": "Allow",
    "Principal": "*",
    "Action": ["s3:GetObject"],
    "Resource": ["arn:aws:s3:::$bucket/$prefix/public/*"]
  }]
}
EOF
s3 put-bucket-policy --bucket "$bucket" --policy "file://$work_dir/policy.json"

# 整体覆盖本项目专用桶的配置，使重复初始化不累积生命周期或通知规则。
cat > "$work_dir/lifecycle.json" <<EOF
{
  "Rules": [{
    "ID": "dev-temp-expire",
    "Status": "Enabled",
    "Filter": {"Prefix": "$prefix/temp/"},
    "Expiration": {"Days": 1}
  }]
}
EOF
s3 put-bucket-lifecycle-configuration --bucket "$bucket" --lifecycle-configuration "file://$work_dir/lifecycle.json"

cat > "$work_dir/notification.json" <<EOF
{
  "QueueConfigurations": [{
    "Id": "media-temp-post",
    "QueueArn": "arn:minio:sqs::MEDIA:webhook",
    "Events": ["s3:ObjectCreated:Post"],
    "Filter": {"Key": {"FilterRules": [{"Name": "prefix", "Value": "$prefix/temp/"}]}}
  }]
}
EOF
s3 put-bucket-notification-configuration --bucket "$bucket" --notification-configuration "file://$work_dir/notification.json"

echo "MinIO 开发桶 $bucket 初始化完成。"
