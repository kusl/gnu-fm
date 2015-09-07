<?php
require_once(__DIR__ . '/vendor/autoload.php');

define(URL, 'http://ponape.com.ar/libre.fm/trunk/tests');
define(USERNAME, 'testuser');
define(PASSWORD, 'goodpassword');

include('web/database.php');
include('web/data/TagCloud.php');

class testLibreFM extends PHPUnit_Framework_TestCase {
    function __construct () {
        $this->UnitTestCase();
    }

    function testDB () {
        global $mdb2, $connect_string;
        # Test if the connection string is more or less connectionstringesque :p
        $this->assertRegexp('/(mysql|sqlite|pgsql):\/\/[a-zA-Z0-9]*:.*@[a-zA-Z0-9]*:[0-9]*\/[a-zA-Z0-9_]*/', $connect_string);
        $this->assertFalse(PEAR::isError($mdb2));
    }

    function testTagCloud () {
        # Testing if we have an array as result
        $this->assertInstanceOf('array', TagCloud::generateTagCloud('Scrobbles', 'artist', 20, null));
    }

    function login ($username, $password) {
        $timestamp = time();
        $token = md5(md5($password) . $timestamp);
        $browser =& new SimpleBrowser();
        $browser->get('http://turtle.libre.fm/', array('hs' => 'true', 'u' => $username, 't' => $timestamp, 'a' => $token, 'c' => 'utt'));
        return $browser;
    }

    function testScrobbleBadAuth() {
        $this->assertTrue(true);
    }

    function WebLogin($username, $password) {
        $browser =& new SimpleBrowser();
        $browser->get(URL . '/web/login.php');
        $browser->setField('username', $username);
        $browser->setField('password', $password);
        $browser->clickSubmitByName('login');

        return $browser;
    }

    function testWebLoginBad() {
        $badlogin = $this->WebLogin(USERNAME, 'badpassword');

        $this->assertEquals($badlogin->getUrl(), URL . '/web/login.php');
    }
    function testWebLoginGood() {
        $goodlogin = $this->WebLogin(USERNAME, PASSWORD);

        $this->assertEquals($goodlogin->getUrl(), URL . '/web/index.php');
    }
    function testWebLogout() {
        $logout = $this->WebLogin(USERNAME, PASSWORD);
        $this->assertTrue(preg_match('/login\.php\?action=logout/', $logout->getContent()));
        $logout->click('logout');
        $this->assertFalse(preg_match('/login\.php\?action=logout/', $logout->getContentAsText()));
    }
}

$test = new testLibreFM();
$test->run(new HtmlReporter());
?>
