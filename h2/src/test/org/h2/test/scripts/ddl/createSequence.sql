-- Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
-- and the EPL 1.0 (http://h2database.com/html/license.html).
-- Initial Developer: H2 Group
--

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY 1 MINVALUE 0 MAXVALUE 1;
> ok

DROP SEQUENCE SEQ;
> ok

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY 1 MINVALUE 0 MAXVALUE 0;
> exception SEQUENCE_ATTRIBUTES_INVALID

CREATE SEQUENCE SEQ START WITH 1 INCREMENT BY 1 MINVALUE 1 MAXVALUE 0;
> exception SEQUENCE_ATTRIBUTES_INVALID

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY 0 MINVALUE 0 MAXVALUE 1;
> exception SEQUENCE_ATTRIBUTES_INVALID

CREATE SEQUENCE SEQ START WITH 1 INCREMENT BY 1 MINVALUE 2 MAXVALUE 10;
> exception SEQUENCE_ATTRIBUTES_INVALID

CREATE SEQUENCE SEQ START WITH 20 INCREMENT BY 1 MINVALUE 1 MAXVALUE 10;
> exception SEQUENCE_ATTRIBUTES_INVALID

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY 9223372036854775807 MINVALUE -9223372036854775808 MAXVALUE 9223372036854775807;
> ok

DROP SEQUENCE SEQ;
> ok

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY -9223372036854775808 MINVALUE -9223372036854775808 MAXVALUE 9223372036854775807;
> ok

DROP SEQUENCE SEQ;
> ok

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY -9223372036854775808 MINVALUE -1 MAXVALUE 9223372036854775807;
> ok

DROP SEQUENCE SEQ;
> ok

CREATE SEQUENCE SEQ START WITH 0 INCREMENT BY -9223372036854775808 MINVALUE 0 MAXVALUE 9223372036854775807;
> exception SEQUENCE_ATTRIBUTES_INVALID

