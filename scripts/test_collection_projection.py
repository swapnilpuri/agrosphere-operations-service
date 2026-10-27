"""AC24 test-only projection/reconciliation harness. No shipped consumer or service DB access.

Uses real producer exports from CollectionServiceIT and only in-memory SQLite.
Full snapshots may bridge version gaps; a gap is retained for reconciliation.
"""
import hashlib
import json
import sqlite3
import unittest
from decimal import Decimal
from pathlib import Path
from jsonschema import Draft202012Validator, FormatChecker

ROOT=Path(__file__).resolve().parents[1]


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),default=str)


class Projection:
    def __init__(self):
        self.db=sqlite3.connect(':memory:')
        self.db.executescript('CREATE TABLE seen(id TEXT PRIMARY KEY,digest TEXT); CREATE TABLE current(id TEXT PRIMARY KEY,version INTEGER,snapshot TEXT,gap INTEGER); CREATE TABLE rejected(id TEXT PRIMARY KEY,attempts INTEGER,dlq INTEGER);')

    def apply(self,event,fault=False):
        digest=hashlib.sha256(canonical(event).encode()).hexdigest()
        try:
            name=event['eventType']
            assert name in {'LatexCollectionRecorded','DrcMeasurementRecorded','LatexCollectionAdjusted','LatexCollectionVoided'} and event['eventVersion']==2
            schema=json.loads((ROOT/'src/main/resources/contracts/events/operations/v2'/(name+'.schema.json')).read_text(),parse_float=Decimal)
            Draft202012Validator(schema,format_checker=FormatChecker()).validate(event)
            snapshot=event['payload']['collection']
            assert event['aggregateId']==snapshot['id'] and event['aggregateVersion']==snapshot['version']
        except Exception:
            with self.db:
                self.db.execute('INSERT INTO rejected VALUES (?,1,0) ON CONFLICT(id) DO UPDATE SET attempts=min(attempts+1,3),dlq=(attempts+1>=3)',(event.get('eventId',digest),))
            return 'rejected'
        with self.db:
            seen=self.db.execute('SELECT digest FROM seen WHERE id=?',(event['eventId'],)).fetchone()
            if seen:
                if seen[0]!=digest:raise ValueError('event identity conflict')
                return 'duplicate'
            row=self.db.execute('SELECT version,snapshot,gap FROM current WHERE id=?',(snapshot['id'],)).fetchone()
            version=snapshot['version']; encoded=canonical(snapshot)
            if row and version==row[0] and encoded!=row[1]:raise ValueError('aggregate version conflict')
            self.db.execute('INSERT INTO seen VALUES (?,?)',(event['eventId'],digest))
            if not row or version>row[0]:
                gap=int((row[2] if row else False) or version>(row[0]+1 if row else 0))
                self.db.execute('INSERT INTO current VALUES (?,?,?,?) ON CONFLICT(id) DO UPDATE SET version=excluded.version,snapshot=excluded.snapshot,gap=excluded.gap',(snapshot['id'],version,encoded,gap))
            if fault:raise RuntimeError('injected before projection commit')
        return 'applied'

    def contributions(self):
        result={}
        for (raw,) in self.db.execute('SELECT snapshot FROM current'):
            c=json.loads(raw)
            if c['status']=='VOIDED':continue
            key=(c['collectionDate'],c['dataProvenance'])
            wet,dry=result.get(key,(Decimal(0),Decimal(0)))
            result[key]=(wet+Decimal(str(c['wetLatexKg'])),dry+Decimal(str(c['dryRubberKg'] or 0)))
        return result


class ReconciliationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.events=[json.loads((ROOT/'target/collection-events'/(name+'.json')).read_text(),parse_float=Decimal) for name in ['LatexCollectionRecorded','DrcMeasurementRecorded','LatexCollectionAdjusted','LatexCollectionVoided']]
        cls.dates=[json.loads((ROOT/'target/collection-date-events'/(name+'.json')).read_text(),parse_float=Decimal) for name in ['LatexCollectionRecorded','LatexCollectionAdjusted']]

    def test_duplicate_stale_gap_and_void_tombstone(self):
        p=Projection()
        for index in [2,0,2,1,3,0]:p.apply(self.events[index])
        self.assertEqual({},p.contributions())
        self.assertEqual((3,1),p.db.execute('SELECT version,gap FROM current').fetchone())
        self.assertEqual(4,p.db.execute('SELECT count(*) FROM seen').fetchone()[0])

    def test_conflicting_same_version_and_same_event_id_are_rejected(self):
        p=Projection();p.apply(self.events[0]); conflicting=json.loads(canonical(self.events[0]),parse_float=Decimal)
        # Change a valid string field while retaining all numeric values/types.
        conflicting=self.events[0]|{'source':'agrosphere-operations-service','eventId':'00000000-0000-0000-0000-000000000001','payload':self.events[0]['payload']|{'collection':self.events[0]['payload']['collection']|{'collectionReference':'OTHER'}}}
        with self.assertRaises(ValueError):p.apply(conflicting)
        with self.assertRaises(ValueError):p.apply(conflicting|{'eventId':self.events[0]['eventId']})
        self.assertEqual(1,p.db.execute('SELECT count(*) FROM seen').fetchone()[0])

    def test_projection_and_dedup_ledger_commit_atomically(self):
        p=Projection()
        with self.assertRaises(RuntimeError):p.apply(self.events[0],fault=True)
        self.assertEqual(0,p.db.execute('SELECT count(*) FROM seen').fetchone()[0]);self.assertEqual({},p.contributions())
        p.apply(self.events[0]);self.assertEqual(1,len(p.contributions()))

    def test_actual_date_correction_moves_only_current_contribution(self):
        p=Projection();p.apply(self.dates[0]);before=p.contributions();p.apply(self.dates[1]);after=p.contributions()
        self.assertNotEqual(set(before),set(after));self.assertEqual(list(before.values()),list(after.values()))
        p.apply(self.dates[0]);self.assertEqual(after,p.contributions())

    def test_invalid_or_unsupported_events_have_bounded_retry_and_dlq(self):
        for invalid in [self.events[0]|{'eventVersion':99},self.events[0]|{'extra':'not permitted'}]:
            p=Projection()
            for attempt in range(10):self.assertEqual('rejected',p.apply(invalid))
            self.assertEqual((3,1),p.db.execute('SELECT attempts,dlq FROM rejected').fetchone());self.assertEqual({},p.contributions())


if __name__=='__main__':unittest.main()
