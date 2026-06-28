import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const prodProperties = readFileSync(
  new URL('../src/main/resources/application-prod.properties', import.meta.url),
  'utf8'
);
const runProdIamLocal = readFileSync(
  new URL('./run-prod-iam-local.sh', import.meta.url),
  'utf8'
);

const apSouthRdsHost = 'database-1.cluster-cz2ywcai8jwm.ap-south-1.rds.amazonaws.com';

assert.match(
  prodProperties,
  /aws\.region=\$\{AWS_REGION:ap-south-1\}/,
  'prod profile should default AWS clients to ap-south-1'
);
assert.match(
  prodProperties,
  /aws\.s3\.bucket\.name=\$\{AWS_S3_BUCKET_NAME:challengesphere-submissions-prod-ap-south-1\}/,
  'prod profile should default S3 media to the ap-south-1 bucket'
);
assert.match(
  prodProperties,
  new RegExp(`spring\\.datasource\\.url=\\$\\{DB_URL:jdbc:postgresql://${apSouthRdsHost.replace(/\./g, '\\.')}:5432/postgres\\?sslmode=require\\}`),
  'prod profile should default JDBC to the ap-south-1 RDS instance'
);
assert.match(
  prodProperties,
  new RegExp(`app\\.datasource\\.iam-auth\\.hostname=\\$\\{DB_IAM_AUTH_HOSTNAME:${apSouthRdsHost.replace(/\./g, '\\.')}\\}`),
  'prod IAM auth should default to the ap-south-1 RDS instance'
);
assert.doesNotMatch(
  `${prodProperties}\n${runProdIamLocal}`,
  /eu-north-1|database-2\.cluster-ctiqk2c4yv8i\.eu-north-1/,
  'prod runtime defaults should not point at eu-north-1'
);

console.log('prod region configuration checks passed');
