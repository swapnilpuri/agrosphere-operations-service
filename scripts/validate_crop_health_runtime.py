"""Actual runtime, HTTP response and event checks using independently pinned II-1 release bytes."""
import json
import hashlib
from pathlib import Path
from jsonschema import Draft202012Validator,FormatChecker
from contract_comparison import resolve
ROOT=Path(__file__).resolve().parents[1];PINS=ROOT/'src/main/resources/contracts'
pin=json.loads((PINS/'CROP_HEALTH_RELEASE.json').read_text(encoding='utf-8'));assert pin['release']=='0.7.0'
for file,expected in pin['files'].items():assert hashlib.sha256((PINS/'previous-0.7.0/operations-service.yaml' if file=='operations-service.yaml' and (PINS/'ALERT_RELEASE.json').exists() else PINS/file).read_bytes()).hexdigest()==expected,file
api=json.loads((PINS/'operations-service.yaml').read_text(encoding='utf-8'));actual=json.loads((ROOT/'target/openapi-runtime.json').read_text(encoding='utf-8'))
methods={'get','post','patch'};operations=lambda a:{(p,m) for p,v in a['paths'].items() for m in v if m in methods}
assert operations(api)==operations(actual) and len(operations(api))==(70 if (PINS/'PRIVACY_RELEASE.json').exists() else 64 if (PINS/'ALERT_RELEASE.json').exists() else 58)
for path,method in operations(api):
    for field in ['requestBody','parameters','responses']:
        assert resolve(api['paths'][path][method].get(field),api)==resolve(actual['paths'][path][method].get(field),actual),(path,method,field)
types={'created':'CHInspectionCreateResult','inspection':'CHInspectionView','incident':'CHIncidentView','inspections':'CHInspectionPage','incidents':'CHIncidentPage',
       'inspection-history':'CHInspectionHistoryPage','incident-history':'CHIncidentHistoryPage'}
for file,shape in types.items():
    value=json.loads((ROOT/'target/crop-health-api'/(file+'.json')).read_text(encoding='utf-8'))
    Draft202012Validator({'$ref':'#/components/schemas/'+shape,'components':api['components']},format_checker=FormatChecker()).validate(value)
seen=set();count=0
for file in (ROOT/'target/crop-health-events').glob('*.json'):
    value=json.loads(file.read_text(encoding='utf-8'));name=value['eventType'];seen.add(name)
    schema=json.loads((PINS/'events/operations/crop-health/v1'/(name+'.schema.json')).read_text(encoding='utf-8'))
    Draft202012Validator(schema,format_checker=FormatChecker()).validate(value);s=value['payload']['snapshot']
    for outer,inner in [('aggregateId','id'),('aggregateVersion','version'),('estateId','estateId'),('blockId','blockId'),('dataProvenance','dataProvenance')]:assert value[outer]==s[inner]
    assert not {'summary','details','findings','reason','recordedBy','createdBy','updatedBy','resolutionNote'}&set(s)
    if name.endswith('Changed'):assert value['payload']['previousVersion']==s['version']-1
    count+=1
assert seen=={'FieldInspectionRecorded','FieldInspectionChanged','CropHealthIncidentReported','CropHealthIncidentChanged'}
print(f'PASS: {len(operations(api))} actual operations, seven closed actual HTTP shapes, {count} actual envelopes/all four minimal families and exact 0.7.0 pins')
