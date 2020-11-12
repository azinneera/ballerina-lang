// Copyright (c) 2020 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
//
// WSO2 Inc. licenses this file to you under the Apache License,
// Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

function mappingMatchPattern1(any v) returns anydata {
    match v {
        {w:1, x:2, y:3,  ...var a} => {
            return a["z"];
        }
        {x:2, y:3, ...var a} => {
            return a["z"];
        }
        _ => {
            return "No match";
        }
    }
}

function testMappingMatchPattern1() {
    assertEquals(3, mappingMatchPattern1({x:2, y:3, "z":3, w:4}));
    assertEquals("3", mappingMatchPattern1({w:1, x:2, y:3, "z":"3"}));
    assertEquals("No match", mappingMatchPattern1({x:3, y:3, "z":3, w:4}));
}

function mappingMatchPattern2(record { int x; int y; int z1; int z2; } v) returns anydata {
    match v {
        {x:2, y:3, z1:5, ...var a} => {
            return a["z2"];
        }
        {x:2, y:3, ...var a} => {
            return a["z2"];
        }
        _ => {
            return "No match";
        }
    }
}

function testMappingMatchPattern2() {
    assertEquals(22, mappingMatchPattern2({x:2, y:3, z1:5, z2:22}));
    assertEquals(22, mappingMatchPattern2({x:2, y:3, z1:6, z2:22}));
    assertEquals("No match", mappingMatchPattern2({x:2, y:2, z1:6, z2:22}));
}

function mappingMatchPattern3(map<int> v) returns anydata {
    match v {
        {x:2, y:3, z1:5, ...var a} => {
            return a["z2"];
        }
        {x:2, y:3, ...var a} => {
            return a["z2"];
        }
        _ => {
            return "No match";
        }
    }
}

function testMappingMatchPattern3() {
    assertEquals(22, mappingMatchPattern3({x:2, y:3, z1:5, z2:22}));
    assertEquals(22, mappingMatchPattern3({x:2, y:3, z1:6, z2:22}));
    assertEquals("No match", mappingMatchPattern3({x:2, y:2, z1:6, z2:22}));
}

function mappingMatchPattern4(record {|int a; int b; string...;|} v) returns (string|int)? {
    match v {
        {a: 2, ...var rst} => {
            map<string|int> mp = rst;
            return mp["c"];
        }
    }
    return "No match";
}

function testMappingMatchPattern4() {
    assertEquals("34", mappingMatchPattern4({a:2, b:3, "c":"34"}));
}

function assertEquals(anydata expected, anydata actual) {
    if expected == actual {
        return;
    }

    panic error("expected '" + expected.toString() + "', found '" + actual.toString () + "'");
}
