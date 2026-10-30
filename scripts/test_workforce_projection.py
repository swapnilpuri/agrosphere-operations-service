"""Test-only full-snapshot consumer policy; no production consumer or service database access."""
import copy
import hashlib
import json
import sqlite3
import unittest
import uuid
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT = Path(__file__).resolve().parents[1]


class Projection:
    def __init__(self):
        self.db = sqlite3.connect(':memory:')
        self.db.executescript('CREATE TABLE seen(id TEXT PRIMARY KEY,digest TEXT); CREATE TABLE current(id TEXT PRIMARY KEY,version INTEGER,snapshot TEXT,gap INTEGER);')

    def apply(self, event, fault=False):
        schema = json.loads((ROOT/'src/main/resources/contracts/events/operations/workforce/v1'/(event['eventType']+'.schema.json')).read_text())
        Draft202012Validator(schema, format_checker=FormatChecker()).validate(event)
        snapshot = event['payload']['snapshot']; root = event['aggregateId']; version = event['aggregateVersion']
        assert root == snapshot.get('registrationId', snapshot.get('attendanceId')) and version == snapshot['version']
        encoded = json.dumps(snapshot, sort_keys=True); digest = hashlib.sha256(json.dumps(event,sort_keys=True).encode()).hexdigest()
        with self.db:
            seen = self.db.execute('SELECT digest FROM seen WHERE id=?',(event['eventId'],)).fetchone()
            if seen:
                if seen[0] != digest: raise ValueError('conflicting event ID')
                return 'duplicate'
            current = self.db.execute('SELECT version,snapshot,gap FROM current WHERE id=?',(root,)).fetchone()
            if current and current[0] == version and current[1] != encoded: raise ValueError('conflicting version')
            self.db.execute('INSERT INTO seen VALUES (?,?)',(event['eventId'],digest))
            if not current or version > current[0]:
                gap = int((current[2] if current else False) or version > (current[0]+1 if current else 0))
                self.db.execute('INSERT INTO current VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,snapshot=excluded.snapshot,gap=excluded.gap',(root,version,encoded,gap))
            if fault: raise RuntimeError('injected transaction failure')
        return 'applied'


class WorkforceProjectionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.events = [json.loads(p.read_text()) for p in (ROOT/'target/workforce-events').glob('*.json')]
        assert len({e['eventType'] for e in cls.events}) == 4, 'Run WorkforceServiceIT first'

    def projection(self):
        p=Projection();self.addCleanup(p.db.close);return p

    def test_duplicates_reordering_gaps_and_void_tombstones(self):
        p=self.projection()
        for event in sorted(self.events,key=lambda e:e['aggregateVersion'],reverse=True):
            self.assertEqual('applied',p.apply(event));self.assertEqual('duplicate',p.apply(event))
        for root in {e['aggregateId'] for e in self.events}:
            latest=max((e for e in self.events if e['aggregateId']==root),key=lambda e:e['aggregateVersion'])
            version,snapshot,gap=p.db.execute('SELECT version,snapshot,gap FROM current WHERE id=?',(root,)).fetchone()
            self.assertEqual(latest['aggregateVersion'],version);self.assertEqual(latest['payload']['snapshot'],json.loads(snapshot))
            self.assertEqual(int(version>0),gap)
        voids=[e for e in self.events if e['payload']['snapshot'].get('status')=='VOIDED']
        self.assertTrue(voids)
        tombstone=self.projection();tombstone.apply(voids[0])
        older=[e for e in self.events if e['aggregateId']==voids[0]['aggregateId'] and e['aggregateVersion']<voids[0]['aggregateVersion']]
        for event in older:tombstone.apply(event)
        self.assertEqual('VOIDED',json.loads(tombstone.db.execute('SELECT snapshot FROM current').fetchone()[0])['status'])

    def test_conflicting_identity_version_and_atomic_dedup(self):
        event=next(e for e in self.events if e['eventType']=='WorkerAttendanceRecorded');p=self.projection()
        with self.assertRaises(RuntimeError):p.apply(event,fault=True)
        self.assertEqual(0,p.db.execute('SELECT count(*) FROM seen').fetchone()[0]);self.assertEqual(0,p.db.execute('SELECT count(*) FROM current').fetchone()[0])
        p.apply(event);conflict=copy.deepcopy(event);conflict['payload']['snapshot']['status']='ABSENT' if event['payload']['snapshot']['status']!='ABSENT' else 'PRESENT'
        with self.assertRaises(ValueError):p.apply(conflict)
        conflict['eventId']=str(uuid.uuid4())
        with self.assertRaises(ValueError):p.apply(conflict)
        self.assertEqual(1,p.db.execute('SELECT count(*) FROM seen').fetchone()[0])


if __name__=='__main__':unittest.main()
