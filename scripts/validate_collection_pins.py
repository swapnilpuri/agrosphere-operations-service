"""Validate local release hashes; this does not certify the unfinished runtime API."""
import hashlib
import json
import argparse
from decimal import Decimal
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1] / "src/main/resources/contracts"
manifest = json.loads((ROOT / "COLLECTION_RELEASE.json").read_text(encoding="utf-8"))
assert manifest["release"] == "0.5.0"
for name, expected in manifest["files"].items():
    path=ROOT/name
    if name=='operations-service.yaml' and (ROOT/'WORKFORCE_RELEASE.json').exists():path=ROOT/'previous-0.5.0/operations-service.yaml'
    assert hashlib.sha256(path.read_bytes()).hexdigest() == expected, name
api = json.loads((ROOT/"operations-service.yaml").read_text(encoding="utf-8"))
assert sum(len(methods) for methods in api["paths"].values()) == (42 if (ROOT/'WORKFORCE_RELEASE.json').exists() else 27)
if (ROOT/'WORKFORCE_RELEASE.json').exists():
    workforce=json.loads((ROOT/'WORKFORCE_RELEASE.json').read_text())
    assert workforce['release']=='0.6.0'
    for name,expected in workforce['files'].items():assert hashlib.sha256((ROOT/name).read_bytes()).hexdigest()==expected,name
    old=json.loads((ROOT/'previous-0.5.0/operations-service.yaml').read_text())
    for path,body in old['paths'].items():assert api['paths'][path]==body,path
for name in (ROOT/"events/operations/v2").glob("*.json"):
    schema = json.loads(name.read_text(encoding="utf-8"))
    assert schema["properties"]["eventVersion"]["const"] == 2
assert len(list((ROOT/"events/operations/v2").glob("*.json"))) == 4
print("PASS: five accepted 0.5.0 artifacts pinned locally; runtime API acceptance remains separate")

parser=argparse.ArgumentParser()
parser.add_argument("--snapshots",type=Path)
parser.add_argument("--events",type=Path)
parser.add_argument("--api-responses",type=Path)
args=parser.parse_args()
if args.snapshots:
    from jsonschema import Draft202012Validator,FormatChecker
    schema=json.loads((ROOT/"events/operations/v2/LatexCollectionRecorded.schema.json").read_text(encoding="utf-8"),parse_float=Decimal)
    view={"$ref":"#/$defs/CollectionView","$defs":schema["$defs"]}
    files=list(args.snapshots.glob("*.json"))
    assert len(files)>=4,"Expected actual created/measured/corrected/void snapshots"
    for file in files:
        value=json.loads(file.read_text(encoding="utf-8"),parse_float=Decimal)
        Draft202012Validator(view,format_checker=FormatChecker()).validate(value)
    print(f"PASS: {len(files)} actual JDBC/domain snapshots match pinned CollectionView schema")
if args.events or args.api_responses:
    from jsonschema import Draft202012Validator,FormatChecker
    from decimal import ROUND_HALF_UP
    if args.events:
        names={'LatexCollectionRecorded','DrcMeasurementRecorded','LatexCollectionAdjusted','LatexCollectionVoided'}
        seen=set()
        for file in args.events.glob('*.json'):
            value=json.loads(file.read_text(encoding='utf-8'),parse_float=Decimal);name=value['eventType'];assert name in names
            schema=json.loads((ROOT/'events/operations/v2'/(name+'.schema.json')).read_text(encoding='utf-8'),parse_float=Decimal)
            Draft202012Validator(schema,format_checker=FormatChecker()).validate(value)
            c=value['payload']['collection'];seen.add(name)
            for field,target in [('aggregateId','id'),('aggregateVersion','version'),('estateId','estateId'),('blockId','blockId'),('dataProvenance','dataProvenance')]:assert value[field]==c[target],field
            m=c['currentMeasurement']
            if m is not None:
                assert m['collectionId']==c['id'] and m['dataProvenance']==c['dataProvenance']
                exact=Decimal(c['wetLatexKg'])*Decimal(m['drcPercent'])/100
                assert c['dryRubberExactKg']==exact
                assert c['dryRubberKg']==exact.quantize(Decimal('0.001'),rounding=ROUND_HALF_UP)
            if name=='DrcMeasurementRecorded':assert value['payload']['measurementId']==m['id']
            if name in {'LatexCollectionAdjusted','LatexCollectionVoided'}:
                assert value['payload']['previousVersion']==c['version']-1==value['payload']['adjustment']['beforeVersion']
                assert value['payload']['adjustment']['afterVersion']==c['version']
        assert seen==names,'Missing actual collection event types'
        print('PASS: four actual v2 outbox event families, identities, versions and exact calculations')
    if args.api_responses:
        api=json.loads((ROOT/'operations-service.yaml').read_text(encoding='utf-8'),parse_float=Decimal)
        types={'history':'CollectionHistoryPage','measurements':'CollectionDrcMeasurementPage','summary':'CollectionSummary','created':'CollectionView','list':'CollectionPage'}
        checked=0
        for name,shape in types.items():
            file=args.api_responses/(name+'.json')
            if file.exists():
                schema={'$ref':'#/components/schemas/'+shape,'components':api['components']}
                Draft202012Validator(schema,format_checker=FormatChecker()).validate(json.loads(file.read_text(encoding='utf-8'),parse_float=Decimal));checked+=1
        assert checked>=3,'Missing actual HTTP evidence'
        print(f'PASS: {checked} actual HTTP response shapes match pinned closed schemas')
