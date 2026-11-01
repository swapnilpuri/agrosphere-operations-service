"""AN-1 actual HTTP/OpenAPI + independent accepted byte-pin verification."""
import hashlib
import json
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from contract_comparison import resolve

ROOT=Path(__file__).resolve().parents[1];PINS=ROOT/'src/main/resources/contracts'
pin=json.loads((PINS/'ALERT_RELEASE.json').read_text())
assert pin['release']=='0.8.0' and pin['sourceManifestSha256']=='d96a5885a60e4e16e2b6e196806d26c8e50a8b58d0243d06a8a6651962b3274c'
for name,h in pin['files'].items():assert hashlib.sha256((PINS/'previous-0.8.0'/name if name=='operations-service.yaml' and (PINS/'PRIVACY_RELEASE.json').exists() else PINS/name).read_bytes()).hexdigest()==h,name
api=json.loads((PINS/'operations-service.yaml').read_text());actual=json.loads((ROOT/'target/openapi-runtime.json').read_text())
methods={'get','post','patch'};ops=lambda a:{(p,m) for p,v in a['paths'].items() for m in v if m in methods}
assert ops(api)==ops(actual) and len(ops(api))==(70 if (PINS/"PRIVACY_RELEASE.json").exists() else 64)
for path,method in ops(api):
    for field in ['parameters','requestBody','responses']:
        assert resolve(api['paths'][path][method].get(field),api)==resolve(actual['paths'][path][method].get(field),actual),(path,method,field)
for filename,shape in {'page':'ANAlertPage','detail':'ANAlertView','counts':'ANCounts','read':'ANAlertView','ack':'ANAlertView','history':'ANHistoryPage'}.items():
    value=json.loads((ROOT/f'target/alert-api/{filename}.json').read_text())
    Draft202012Validator({'$ref':'#/components/schemas/'+shape,'components':api['components']},format_checker=FormatChecker()).validate(value)
    for row in value.get('content',[value]):
        assert not {'summary','details','findings','recipients','delivered','readBy'} & set(row)
        if shape!='ANHistoryPage':assert not {'acknowledgedBy','issuer','subject'} & set(row)
    if filename=='ack':assert value['acknowledged'] and not value['unreadForMe']
    if filename=='read':assert not value['unreadForMe'] and not value['acknowledged']
print('PASS: actual operations preserve exact accepted schemas; all six actual alert response shapes and preserved exact 0.8.0 archive validate; ordinary responses exclude actors/source text')
