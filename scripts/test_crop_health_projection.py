"""Test-only consumer policy harness; no shipped Analytics/notification projection."""
import copy
import unittest


class Projection:
    def __init__(self): self.seen=set();self.roots={};self.gaps=set();self.quarantine=[]
    def apply(self,event,fail=False):
        if event['eventId'] in self.seen:return 'duplicate'
        aggregate=event['aggregateId'];state=copy.deepcopy(event['payload']['snapshot']);version=event['aggregateVersion']
        prior=self.roots.get(aggregate)
        if prior is not None and version==prior['version'] and state!=prior:
            self.quarantine.append(copy.deepcopy(event));return 'conflict'
        if fail:raise RuntimeError('SIMULATED failure before atomic dedup/projection commit')
        if prior is None or version>prior['version']:
            if version>(-1 if prior is None else prior['version'])+1:self.gaps.add(aggregate)
            self.roots[aggregate]=state
        self.seen.add(event['eventId']);return 'applied'
    def unresolved_links(self):
        return {state.get('linkedIncidentId') or state.get('inspectionId') for state in self.roots.values()
                if (state.get('linkedIncidentId') or state.get('inspectionId')) not in self.roots
                and (state.get('linkedIncidentId') or state.get('inspectionId')) is not None}


def event(identity,root,version,status='OPEN',**extra):
    return {'eventId':identity,'aggregateId':root,'aggregateVersion':version,'payload':{'snapshot':dict(id=root,version=version,status=status,**extra)}}


class CropProjectionTest(unittest.TestCase):
    def test_duplicate_stale_and_void_tombstone(self):
        p=Projection();p.apply(event('a','incident',0));p.apply(event('b','incident',2,'VOIDED'))
        self.assertEqual('duplicate',p.apply(event('b','incident',2,'VOIDED')));p.apply(event('c','incident',1,'IN_REVIEW'))
        self.assertEqual('VOIDED',p.roots['incident']['status']);self.assertIn('incident',p.gaps)
    def test_equal_revision_conflict_is_quarantined(self):
        p=Projection();p.apply(event('a','incident',0));self.assertEqual('conflict',p.apply(event('b','incident',0,'RESOLVED')))
        self.assertNotIn('b',p.seen);self.assertEqual('OPEN',p.roots['incident']['status']);self.assertEqual(1,len(p.quarantine))
    def test_projection_failure_does_not_commit_dedup(self):
        p=Projection();e=event('a','incident',0)
        with self.assertRaises(RuntimeError):p.apply(e,True)
        self.assertFalse(p.seen);self.assertFalse(p.roots);self.assertEqual('applied',p.apply(e))
    def test_linked_roots_can_arrive_in_either_order_without_fabrication(self):
        inspection=event('a','inspection',0,'RECORDED',linkedIncidentId='incident')
        incident=event('b','incident',0,inspectionId='inspection')
        for first,second in [(inspection,incident),(incident,inspection)]:
            p=Projection();p.apply(first);self.assertEqual(1,len(p.roots));self.assertEqual(1,len(p.unresolved_links()))
            p.apply(second);self.assertFalse(p.unresolved_links());self.assertEqual(2,len(p.roots))


if __name__=='__main__':unittest.main()
