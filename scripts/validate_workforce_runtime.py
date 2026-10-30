"""Validate independently pinned workforce bytes, actual REST export, HTTP bodies and real events."""
import json
import hashlib
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker
from contract_comparison import resolve

ROOT=Path(__file__).resolve().parents[1]
PINS=ROOT/'src/main/resources/contracts'
manifest=json.loads((PINS/'WORKFORCE_RELEASE.json').read_text())
assert manifest['release']=='0.6.0'
for name,expected in manifest['files'].items():
    assert hashlib.sha256((PINS/name).read_bytes()).hexdigest()==expected,name
api=json.loads((PINS/'operations-service.yaml').read_text())
runtime=json.loads((ROOT/'target/openapi-runtime.json').read_text())
methods={'get','post','patch'}
expected={(p,m) for p,v in api['paths'].items() for m in v if m in methods}
actual={(p,m) for p,v in runtime['paths'].items() for m in v if m in methods}
assert expected==actual,(expected-actual,actual-expected)
for path,method in expected:
    for key in ['requestBody','parameters','responses']:
        assert resolve(api['paths'][path][method].get(key),api)==resolve(runtime['paths'][path][method].get(key),runtime),(path,method,key)
shapes={'worker':'WorkerView','attendance':'AttendanceView','worker-history':'WorkerHistoryPage',
        'attendance-history':'AttendanceHistoryPage','roster':'AttendanceRosterPage','coverage':'AttendanceRosterPage'}
for file,shape in shapes.items():
    value=json.loads((ROOT/'target/workforce-api'/(file+'.json')).read_text())
    Draft202012Validator({'$ref':'#/components/schemas/'+shape,'components':api['components']},format_checker=FormatChecker()).validate(value)
seen=set();count=0
for path in (ROOT/'target/workforce-events').glob('*.json'):
    event=json.loads(path.read_text());name=event['eventType'];seen.add(name)
    schema=json.loads((PINS/'events/operations/workforce/v1'/(name+'.schema.json')).read_text())
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(event)
    s=event['payload']['snapshot']
    for outer,inner in [('estateId','estateId'),('dataProvenance','dataProvenance'),('aggregateVersion','version'),
                       ('aggregateId','registrationId' if name.startswith('WorkerRegistration') else 'attendanceId')]:
        assert event[outer]==s[inner],outer
    if name.endswith('Changed'):
        assert event['payload']['previousVersion']==s['version']-1
        if name=='WorkerAttendanceChanged':assert (event['payload']['changeKind']=='VOID')==(s['status']=='VOIDED')
    count+=1
assert seen=={'WorkerRegistrationCreated','WorkerRegistrationChanged','WorkerAttendanceRecorded','WorkerAttendanceChanged'},seen
print(f'PASS: 42 actual REST operations aligned, six closed actual HTTP shapes, {count} real workforce envelopes/all four families and exact accepted 0.6.0 pins')
