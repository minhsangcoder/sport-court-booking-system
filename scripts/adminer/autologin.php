<?php
// Read-only auto-login for the SportHub Adminer. Access is gated by nginx Basic Auth;
// the DB role is "viewer" (SELECT only, secret columns excluded).
class AdminerAutoLogin {
  function credentials() {
    return array('postgres', 'viewer', getenv('ADMINER_VIEWER_PASSWORD'));
  }
  function login($login, $password) {
    return true;
  }
  function loginFormField($name, $heading, $value) {
    if ($name == 'password') return $heading . '<input type="hidden" name="auth[password]" value="x">' . "\n";
    return null;
  }
}
return new AdminerAutoLogin;
