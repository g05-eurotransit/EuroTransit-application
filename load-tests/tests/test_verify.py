import importlib.util
from pathlib import Path
import unittest
spec = importlib.util.spec_from_file_location('verify', Path(__file__).parents[1] / 'verify.py')
module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
class VerifyTests(unittest.TestCase):
    def case(self):
        row={'runId':'run','orderId':'a','outcome':'confirmed','status':'CONFIRMED','fixture':{'routeId':'r','travelDate':'2099-01-01','seatId':'1A','unitPrice':24.90}}
        evidence={'runId':'run','extraction':'COMPLETE','orders':[{'id':'a','status':'CONFIRMED','route_id':'r','travel_date':'2099-01-01','seats':'1A','total_amount':'24.90','currency':'EUR'}],'payments':[{'order_id':'a','status':'AUTHORIZED','provider_reference':'charge-a','amount':'24.90','currency':'EUR'}],'reservations':[{'order_id':'a','reservation_id':'res','status':'CONFIRMED','route_id':'r','travel_date':'2099-01-01'}],'inventory':[{'route_id':'r','travel_date':'2099-01-01','seat_id':'1A','reservation_id':'res','status':'SOLD'}]}
        evidence['providerEffects']={'extraction':'COMPLETE','authorizations':[{'order_id':'a','status':'SUCCEEDED','provider_reference':'charge-a','amount':'24.90','currency':'EUR'}]}
        return [row],evidence
    def test_valid(self):
        r,e=self.case();self.assertEqual(module.verify(r,e),[])
    def test_duplicate_charge(self):
        r,e=self.case();e['payments']*=2;self.assertIn('duplicate_authorization:a',module.verify(r,e))
    def test_wrong_owner(self):
        r,e=self.case();e['inventory'][0]['reservation_id']='other';self.assertIn('seat_owner_mismatch:a',module.verify(r,e))
    def test_missing_evidence(self):
        r,e=self.case();e['extraction']='INCOMPLETE';self.assertEqual(module.verify(r,e),['evidence_incomplete'])
    def test_wrong_amount(self):
        r,e=self.case();e['payments'][0]['amount']='99';self.assertIn('payment_amount_or_currency_mismatch:a',module.verify(r,e))
    def test_changed_terminal(self):
        r,e=self.case();e['orders'][0]['status']='FAILED';self.assertIn('confirmed_order_changed:a',module.verify(r,e))
    def test_no_checkout(self):
        r,e=self.case();r[0]={'runId':'run','outcome':'technical_failure'};e.update(orders=[],payments=[],reservations=[],inventory=[]);self.assertIn('no_confirmed_checkout',module.verify(r,e))
    def test_wrong_fixture(self):
        r,e=self.case();e['orders'][0]['travel_date']='2099-01-02';self.assertIn('order_fixture_mismatch:a',module.verify(r,e))
    def test_missing_provider(self):
        r,e=self.case();del e['providerEffects'];self.assertIn('provider_effects_unverified',module.verify(r,e))
    def test_unresolved(self):
        r,e=self.case();e['orders'][0]['status']='PAYMENT_PENDING';self.assertIn('unresolved_order:a',module.verify(r,e))
if __name__=='__main__':unittest.main()
