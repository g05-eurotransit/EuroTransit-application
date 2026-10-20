INSERT INTO routes VALUES
('rt-to-mi-001',1,'Torino Porta Nuova','Milano Centrale','07:00','08:05','Europe/Rome','Frecciarossa',ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN']),
('rt-mi-pa-002',2,'Milano Centrale','Paris Gare de Lyon','09:10','16:22','Europe/Rome','TGV',ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN']),
('rt-mi-zu-003',3,'Milano Centrale','Zürich HB','11:10','14:27','Europe/Rome','EuroCity',ARRAY['MON','TUE','WED','THU','FRI','SAT','SUN']),
('rt-ro-vi-004',4,'Roma Termini','Wien Hbf','19:20','08:42','Europe/Rome','Nightjet',ARRAY['MON','TUE','WED','THU','FRI']),
('rt-to-ly-005',5,'Torino Porta Susa','Lyon Part-Dieu','14:31','18:20','Europe/Rome','TGV',ARRAY['MON','TUE','WED','THU','FRI']);

INSERT INTO offers VALUES
('of-001','rt-to-mi-001','STANDARD',24.90,'EUR',false,NULL,1,NULL),
('of-002','rt-to-mi-001','BUSINESS',49.90,'EUR',true,5,1,NULL),
('of-003','rt-mi-pa-002','STANDARD',89.00,'EUR',false,NULL,1,NULL),
('of-004','rt-mi-zu-003','STANDARD',64.00,'EUR',false,NULL,1,NULL),
('of-005','rt-ro-vi-004','SLEEPER',129.00,'EUR',true,1440,1,NULL),
('of-006','rt-to-ly-005','STANDARD',45.00,'EUR',false,NULL,1,NULL);
