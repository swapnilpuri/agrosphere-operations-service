import unittest
from contract_comparison import resolve


class ContractComparisonTest(unittest.TestCase):
    def test_required_order_only_is_irrelevant(self):
        self.assertEqual(resolve({'required':['b','a']},{}),resolve({'required':['a','b']},{}))

    def test_missing_required_field_or_changed_constraint_is_detected(self):
        self.assertNotEqual(resolve({'required':['a','b']},{}),resolve({'required':['a']},{}))
        for field,left,right in [('additionalProperties',False,True),('multipleOf',0.001,0.01),('maximum',100,101),('format','uuid','string')]:
            self.assertNotEqual(resolve({field:left},{}),resolve({field:right},{}))

    def test_ref_sibling_constraints_cannot_disappear(self):
        document={'components':{'schemas':{'Name':{'type':'string','maxLength':200}}}}
        self.assertNotEqual(resolve({'$ref':'#/components/schemas/Name'},document),resolve({'$ref':'#/components/schemas/Name','maxLength':1},document))


if __name__=='__main__':unittest.main()
