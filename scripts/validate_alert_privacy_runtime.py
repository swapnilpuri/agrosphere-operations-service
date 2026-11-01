"""Independent 0.9.0 pins plus real signed-JWT response/OpenAPI exports, never sibling source."""
import hashlib,json
from pathlib import Path
from jsonschema import Draft202012Validator,FormatChecker
from contract_comparison import resolve
ROOT=Path(__file__).resolve().parents[1]; PINS=ROOT/'src/main/resources/contracts'
pin=json.loads((PINS/'PRIVACY_RELEASE.json').read_text(encoding='utf-8'))
assert pin['release']=='0.9.0' and pin['sourceManifestSha256']=='ea6402a205d1da7a0801090b0acabac58ae164ba6544b474d709d8a4a608286b'
for path,sha in pin['files'].items():assert hashlib.sha256((PINS/path).read_bytes()).hexdigest()==sha
assert hashlib.sha256((PINS/'previous-0.8.0/operations-service.yaml').read_bytes()).hexdigest()=='12daf94f17e8b4fded507c1f9a27a72deb7244c9a8b20013df131e748213e937'
api=json.loads((PINS/'operations-service.yaml').read_text(encoding='utf-8'))
live=json.loads((ROOT/'target/openapi-runtime.json').read_text(encoding='utf-8'))
ops=lambda doc:{(p,m) for p,body in doc['paths'].items() for m in body if m in {'get','post','patch'}}
assert ops(api)==ops(live) and len(ops(api))==70
for p,m in ops(api):
 for field in ['parameters','requestBody','responses']:assert resolve(api['paths'][p][m].get(field),api)==resolve(live['paths'][p][m].get(field),live),(p,m,field)
shapes={'page':'ANPAlertPage','detail':'ANPAlertView','counts':'ANCounts','read':'ANPAlertView','ack':'ANPAlertView','history':'ANPHistoryPage','expired-read':'ANPAlertView','redacted':'ANPAlertView','expired-history':'ANPHistoryPage','partial-history':'ANPHistoryPage'}
values={}
for name,shape in shapes.items():
 value=json.loads((ROOT/'target/privacy-api'/f'{name}.json').read_text(encoding='utf-8'));values[name]=value
 Draft202012Validator({'$ref':'#/components/schemas/'+shape,'components':api['components']},format_checker=FormatChecker()).validate(value)
 if shape in {'ANPAlertView','ANPAlertPage'}:
  for item in value.get('content',[value]):assert not {'issuer','subject','acknowledgedBy','summary','details','delivered','readBy'}&set(item)
assert values['expired-read']['readState']=='EXPIRED' and not values['expired-read']['unreadForMe'] and values['expired-read']['readAt'] is None
assert values['redacted']['acknowledged'] and values['redacted']['acknowledgementState']=='REDACTED' and values['redacted']['acknowledgedAt'] is None
assert values['expired-history']['retentionState']=='EXPIRED' and values['expired-history']['totalElements']==0
assert values['partial-history']['retentionState']=='PARTIAL' and values['partial-history']['lastExpiredAt'] is not None
print('PASS: 70 actual operations, exact accepted 0.9.0 pin/0.8.0 archive, ten real privacy response shapes and expired/redacted/partial semantics')
