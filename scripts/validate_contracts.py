"""Check pinned operations contracts against runtime artifacts; fail if missing."""
import copy,json
from pathlib import Path
import yaml
from jsonschema import Draft202012Validator,FormatChecker
from openapi_spec_validator import validate
root=Path(__file__).resolve().parents[1]
api=yaml.safe_load((root/'src/main/resources/contracts/operations-service.yaml').read_text())
validate(api)
runtime=json.loads((root/'target/openapi-runtime.json').read_text())
expected={(p,m) for p,methods in api['paths'].items() for m in methods if m in ('get','post','patch')}
actual={(p,m) for p,methods in runtime['paths'].items() for m in methods if m in ('get','post','patch')}
assert expected==actual,(expected-actual,actual-expected)
# Resolve local response and request schemas, preserving meaningful constraints.
def resolve(node,doc):
    if isinstance(node,list): return [resolve(x,doc) for x in node]
    if not isinstance(node,dict): return node
    if '$ref' in node:
        target=doc
        for key in node['$ref'].removeprefix('#/').split('/'): target=target[key]
        return resolve(target,doc)
    return {k:resolve(v,doc) for k,v in node.items() if k not in ('description','title','example','examples')}
for p,m in expected:
    pinned=api['paths'][p][m];live=runtime['paths'][p][m]
    for key in ('requestBody','parameters','responses'):
        assert resolve(pinned.get(key),api)==resolve(live.get(key),runtime),(p,m,key)
count=0
types=set()
for file in (root/'target/events').glob('*.json'):
    event=json.loads(file.read_text());types.add(event['eventType']);schema=json.loads((root/'src/main/resources/contracts/events'/ (event['eventType']+'.schema.json')).read_text())
    validator=Draft202012Validator(schema,format_checker=FormatChecker());validator.validate(event)
    for key in schema['required']:
        bad=copy.deepcopy(event);del bad[key];assert not validator.is_valid(bad),key
    for key,value in [('eventVersion',2),('eventId','invalid'),('source','wrong'),('unexpected',True)]:
        bad=copy.deepcopy(event);bad[key]=value;assert not validator.is_valid(bad),key
    count+=1
assert types=={'TappingScheduleActivated','TappingAssignmentCreated','TappingAssignmentStarted','TappingCompleted','TappingMissed','TappingAssignmentCancelled'},'Missing event types'
print(f'PASS: {len(expected)} REST operations aligned, {count} real events validated with negative cases')
