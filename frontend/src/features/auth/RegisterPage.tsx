import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, useNavigate } from 'react-router-dom';
import { z } from 'zod';
import { ProblemError } from '../../api/client';
import { useAuth } from './AuthProvider';

const registerSchema = z.object({
  displayName: z.string().trim().min(1, 'Enter your name.').max(100, 'Name must be 100 characters or fewer.'),
  email: z.string().email('Enter a valid email address.').max(200, 'Email must be 200 characters or fewer.'),
  password: z.string().min(8, 'Password must be at least 8 characters.').max(72, 'Password must be 72 characters or fewer.')
    .regex(/^(?=.*[A-Za-z])(?=.*\d).+$/, 'Use at least one letter and one number.'),
});
type RegisterValues = z.infer<typeof registerSchema>;

export function RegisterPage() {
  const { register: createAccount } = useAuth();
  const navigate = useNavigate();
  const [serverError, setServerError] = useState('');
  const { register, handleSubmit, formState: { errors, isSubmitting } } = useForm<RegisterValues>({
    resolver: zodResolver(registerSchema),
  });

  const submit = handleSubmit(async (values) => {
    setServerError('');
    try {
      await createAccount(values);
      navigate('/menu', { replace: true });
    } catch (error) {
      setServerError(error instanceof ProblemError ? error.problem.detail : 'We could not create your account. Try again.');
    }
  });

  return (
    <main className="auth-page">
      <section className="auth-card" aria-labelledby="register-title">
        <Link className="back-link" to="/login">← Back to sign in</Link>
        <div className="brand-mark" aria-hidden="true">W</div>
        <p className="eyebrow">YOUR NEXT GREAT LUNCH</p>
        <h1 id="register-title">Make room for something good.</h1>
        <p className="auth-intro">Create an account to save your preferences and delivery details.</p>
        <form onSubmit={submit} noValidate>
          <label htmlFor="displayName">Your name</label>
          <input id="displayName" autoComplete="name" {...register('displayName')} aria-invalid={Boolean(errors.displayName)} />
          {errors.displayName && <span className="field-error">{errors.displayName.message}</span>}
          <label htmlFor="email">Email address</label>
          <input id="email" type="email" autoComplete="email" {...register('email')} aria-invalid={Boolean(errors.email)} />
          {errors.email && <span className="field-error">{errors.email.message}</span>}
          <label htmlFor="password">Create a password</label>
          <input id="password" type="password" autoComplete="new-password" {...register('password')} aria-invalid={Boolean(errors.password)} />
          {errors.password && <span className="field-error">{errors.password.message}</span>}
          {serverError && <p className="form-error" role="alert">{serverError}</p>}
          <button className="button button-primary auth-submit" type="submit" disabled={isSubmitting}>
            {isSubmitting ? 'Creating account…' : 'Create account'}
          </button>
        </form>
        <p className="auth-switch">Already have an account? <Link to="/login">Sign in</Link></p>
      </section>
      <aside className="auth-art register-art" aria-label="Fresh food, prepared with care">
        <div className="art-copy"><span>Made with care.</span><span>Picked for you.</span></div>
        <div className="art-orbit orbit-one" /><div className="art-orbit orbit-two" />
        <div className="art-plate"><div className="plate-leaf leaf-one" /><div className="plate-leaf leaf-two" /><div className="plate-orange" /></div>
        <p className="art-caption">A little more color in the middle of your day.</p>
      </aside>
    </main>
  );
}
